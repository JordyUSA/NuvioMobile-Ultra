import Foundation
import Network

/// A minimal HTTP server so a Chromecast can pull media from the phone.
///
/// Mirrors `CastLocalServer.kt` (Android): a receiver fetches bytes from its own position on
/// the network, so anything produced locally (a remux or a transcode) and anything already
/// bound to loopback is invisible to the television until it is served from the phone's LAN
/// address.
///
/// Built directly on `Network.framework` rather than a vendored HTTP server library. The
/// original plan called for CocoaHTTPServer, but this session's network access does not extend
/// to fetching third-party source repositories, and `NWListener`/`NWConnection` already cover
/// everything actually needed here: accept a connection, read a request line and headers, and
/// stream back a range-respecting response. That also drops an unmaintained Objective-C
/// dependency in favour of Apple's own supported socket API.
///
/// Range support is not optional: without `206 Partial Content` the receiver cannot seek, and
/// some firmware refuses to start playback at all.
final class CastLocalServer {

    enum Payload {
        /// A file on disk, typically the output of a remux or transcode.
        case localFile(path: String, contentType: String)

        /// A remote URL fetched on the receiver's behalf. Used when the origin needs request
        /// headers the Cast receiver cannot send, or lives on an address only the phone can
        /// reach.
        case proxy(url: String, contentType: String, headers: [String: String])
    }

    private let queue = DispatchQueue(label: "cast-local-server")
    private var listener: NWListener?
    private let routesLock = NSLock()
    private var routes: [String: Payload] = [:]

    private(set) var port: UInt16 = 0

    /// Starts the listener if it is not already running. Safe to call repeatedly.
    @discardableResult
    func start() -> Bool {
        if let listener, listener.state == .ready { return true }

        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            let newListener = try NWListener(using: params, on: .any)

            let readySemaphore = DispatchSemaphore(value: 0)
            var boundPort: UInt16 = 0
            var didFail = false

            newListener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    boundPort = newListener.port?.rawValue ?? 0
                    readySemaphore.signal()
                case .failed, .cancelled:
                    didFail = true
                    readySemaphore.signal()
                default:
                    break
                }
            }
            newListener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }
            newListener.start(queue: queue)

            // start(queue:) is asynchronous; block briefly for the first state transition so
            // callers can publish a URL immediately rather than racing the listener coming up.
            _ = readySemaphore.wait(timeout: .now() + 5)
            if didFail || boundPort == 0 {
                newListener.cancel()
                return false
            }

            listener = newListener
            port = boundPort
            return true
        } catch {
            return false
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        port = 0
        routesLock.lock()
        routes.removeAll()
        routesLock.unlock()
        CastKeepAlive.shared.releaseAll()
    }

    /// Publishes `payload` and returns the URL to hand the receiver, or nil when no usable LAN
    /// address is available (for example while the phone is offline).
    func publish(id: String, payload: Payload) -> String? {
        guard start() else { return nil }
        routesLock.lock()
        let isNew = routes.updateValue(payload, forKey: id) == nil
        routesLock.unlock()
        // Serving means the television is pulling bytes out of this process, so the process
        // has to keep running even when the user switches away. Balanced in unpublish/stop.
        if isNew { CastKeepAlive.shared.retain() }
        guard let host = Self.localAddress() else { return nil }
        return "http://\(host):\(port)/media/\(id)"
    }

    func unpublish(id: String) {
        routesLock.lock()
        let existed = routes.removeValue(forKey: id) != nil
        routesLock.unlock()
        if existed { CastKeepAlive.shared.release() }
    }

    /// The phone's Wi-Fi address. Loopback is useless here: it has to be an address the
    /// television can route to, so only `en0` (Wi-Fi on every iOS device) is considered.
    static func localAddress() -> String? {
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPtr) == 0, let firstAddr = ifaddrPtr else { return nil }
        defer { freeifaddrs(ifaddrPtr) }

        var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = cursor {
            defer { cursor = current.pointee.ifa_next }

            let flags = Int32(current.pointee.ifa_flags)
            guard (flags & IFF_UP) == IFF_UP, (flags & IFF_LOOPBACK) == 0 else { continue }
            guard let addr = current.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else {
                continue
            }
            guard String(cString: current.pointee.ifa_name) == "en0" else { continue }

            var addrIn = sockaddr_in()
            memcpy(&addrIn, addr, MemoryLayout<sockaddr_in>.size)
            var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &addrIn.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN))
            return String(cString: buffer)
        }
        return nil
    }

    // MARK: - Connection handling

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        readRequest(connection: connection, buffered: Data())
    }

    /// Reads until the blank line that ends the request headers. HTTP request lines and
    /// headers are always ASCII, so a byte-oriented search for `\r\n\r\n` is enough; the body
    /// (there is none for GET/HEAD) is not touched.
    private func readRequest(connection: NWConnection, buffered: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffered
            if let data, !data.isEmpty { buffer.append(data) }

            if let error {
                _ = error
                connection.cancel()
                return
            }

            if let range = buffer.range(of: Data([0x0D, 0x0A, 0x0D, 0x0A])) {
                let headerData = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
                self.handleRequest(headerData, connection: connection)
                return
            }

            if isComplete || buffer.count > 16384 {
                connection.cancel()
                return
            }

            self.readRequest(connection: connection, buffered: buffer)
        }
    }

    private func handleRequest(_ headerData: Data, connection: NWConnection) {
        let text = String(decoding: headerData, as: UTF8.self)
        var lines = text.components(separatedBy: "\r\n")
        guard !lines.isEmpty else { return respond(connection, status: 400, reason: "Bad Request") }

        let requestLine = lines.removeFirst().components(separatedBy: " ")
        guard requestLine.count >= 2 else {
            return respond(connection, status: 400, reason: "Bad Request")
        }
        let method = requestLine[0].uppercased()
        let path = requestLine[1]

        var rangeHeader: String?
        for line in lines {
            guard let separator = line.firstIndex(of: ":") else { continue }
            let name = line[line.startIndex..<separator].trimmingCharacters(in: .whitespaces)
            if name.caseInsensitiveCompare("Range") == .orderedSame {
                rangeHeader = line[line.index(after: separator)...].trimmingCharacters(in: .whitespaces)
            }
        }

        guard method == "GET" || method == "HEAD" else {
            return respond(connection, status: 405, reason: "Method Not Allowed")
        }

        let id = String(path.dropFirst("/media/".count)).components(separatedBy: "?").first ?? ""
        routesLock.lock()
        let payload = routes[id]
        routesLock.unlock()

        guard let payload else {
            return respond(connection, status: 404, reason: "Not Found")
        }

        switch payload {
        case let .localFile(path, contentType):
            serveFile(path: path, contentType: contentType, rangeHeader: rangeHeader, headOnly: method == "HEAD", connection: connection)
        case let .proxy(url, contentType, headers):
            serveProxy(url: url, contentType: contentType, headers: headers, rangeHeader: rangeHeader, headOnly: method == "HEAD", connection: connection)
        }
    }

    // MARK: - Local file

    private func serveFile(path: String, contentType: String, rangeHeader: String?, headOnly: Bool, connection: NWConnection) {
        let fileManager = FileManager.default
        guard let attributes = try? fileManager.attributesOfItem(atPath: path),
              let total = (attributes[.size] as? NSNumber)?.int64Value else {
            return respond(connection, status: 404, reason: "Not Found")
        }

        guard let (start, end) = Self.parseRange(rangeHeader, total: total) else {
            sendHeaders(
                connection: connection, status: 416, reason: "Requested Range Not Satisfiable",
                contentType: contentType, contentLength: 0, contentRange: "bytes */\(total)"
            ) { connection.cancel() }
            return
        }
        let length = end - start + 1

        sendHeaders(
            connection: connection,
            status: rangeHeader != nil ? 206 : 200,
            reason: rangeHeader != nil ? "Partial Content" : "OK",
            contentType: contentType,
            contentLength: length,
            contentRange: rangeHeader != nil ? "bytes \(start)-\(end)/\(total)" : nil
        ) {
            guard !headOnly, let handle = FileHandle(forReadingAtPath: path) else {
                connection.cancel()
                return
            }
            handle.seek(toFileOffset: UInt64(start))
            self.streamFile(handle: handle, remaining: length, connection: connection)
        }
    }

    private func streamFile(handle: FileHandle, remaining: Int64, connection: NWConnection) {
        guard remaining > 0 else {
            try? handle.close()
            connection.cancel()
            return
        }
        let want = Int(min(remaining, Int64(Self.bufferSize)))
        guard let chunk = try? handle.read(upToCount: want), !chunk.isEmpty else {
            try? handle.close()
            connection.cancel()
            return
        }
        connection.send(content: chunk, completion: .contentProcessed { [weak self] error in
            // Stop reading once the socket is gone. The receiver closes a range connection as
            // soon as it has what it asked for, and without this the file kept being read to
            // its end into a connection nobody was listening to.
            guard error == nil else {
                try? handle.close()
                connection.cancel()
                return
            }
            self?.streamFile(handle: handle, remaining: remaining - Int64(chunk.count), connection: connection)
        })
    }

    // MARK: - Proxy

    private func serveProxy(url: String, contentType: String, headers: [String: String], rangeHeader: String?, headOnly: Bool, connection: NWConnection) {
        guard let requestUrl = URL(string: url) else {
            return respond(connection, status: 502, reason: "Bad Gateway")
        }

        var request = URLRequest(url: requestUrl, timeoutInterval: 30)
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        if let rangeHeader { request.setValue(rangeHeader, forHTTPHeaderField: "Range") }

        // One delegate queue per transfer. Sharing a single four-slot queue across every
        // transfer was enough to break casting outright about a minute in: the receiver opens
        // a fresh ranged request for each chunk it buffers and abandons the previous one, and
        // a delegate holds its slot while a chunk is handed to the socket, so a few ranges
        // filled the queue and the transfer that was actually feeding the television starved.
        let delegateQueue = OperationQueue()
        delegateQueue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: .ephemeral, delegate: nil, delegateQueue: delegateQueue)

        let streamer = ProxyStreamer(
            contentType: contentType,
            headOnly: headOnly,
            onResponse: { [weak self] status, reason, contentLength, contentRange, ready in
                self?.sendHeaders(
                    connection: connection, status: status, reason: reason,
                    contentType: contentType, contentLength: contentLength, contentRange: contentRange,
                    completion: ready
                )
            },
            onData: { data, ready in
                connection.send(content: data, completion: .contentProcessed { error in
                    // Nothing is listening any more: drop the fetch behind it rather than
                    // pulling the rest of the file across the network for a dead socket.
                    if error != nil { session.invalidateAndCancel() }
                    ready()
                })
            },
            onFinish: {
                connection.cancel()
                session.finishTasksAndInvalidate()
            }
        )

        let task = session.dataTask(with: request)
        task.delegate = streamer
        streamer.retain(task)

        // The receiver hangs up the moment it has the range it wanted. Cancelling the upstream
        // fetch with it is what keeps abandoned downloads from accumulating and competing for
        // bandwidth with the request that replaced them. Installed before `resume` so a
        // connection that has already gone is caught too.
        connection.stateUpdateHandler = { state in
            switch state {
            case .cancelled, .failed:
                session.invalidateAndCancel()
            default:
                break
            }
        }

        task.resume()
    }

    // MARK: - Response helpers

    private func respond(_ connection: NWConnection, status: Int, reason: String) {
        sendHeaders(connection: connection, status: status, reason: reason, contentType: "text/plain", contentLength: 0, contentRange: nil) {
            connection.cancel()
        }
    }

    private func sendHeaders(
        connection: NWConnection,
        status: Int,
        reason: String,
        contentType: String,
        contentLength: Int64?,
        contentRange: String?,
        completion: @escaping () -> Void
    ) {
        var text = "HTTP/1.1 \(status) \(reason)\r\n"
        text += "Content-Type: \(contentType)\r\n"
        text += "Accept-Ranges: bytes\r\n"
        text += "Connection: close\r\n"
        if let contentLength { text += "Content-Length: \(contentLength)\r\n" }
        if let contentRange { text += "Content-Range: \(contentRange)\r\n" }
        text += "\r\n"

        connection.send(content: Data(text.utf8), completion: .contentProcessed { _ in
            completion()
        })
    }

    private static func parseRange(_ header: String?, total: Int64) -> (Int64, Int64)? {
        guard let header else { return (0, max(total - 1, 0)) }
        guard let spec = header.range(of: "bytes=").map({ String(header[$0.upperBound...]) })?
            .components(separatedBy: ",").first else { return nil }

        let parts = spec.components(separatedBy: "-")
        guard parts.count == 2 else { return nil }
        let startText = parts[0].trimmingCharacters(in: .whitespaces)
        let endText = parts[1].trimmingCharacters(in: .whitespaces)

        if startText.isEmpty {
            guard let suffix = Int64(endText) else { return nil }
            return (max(total - suffix, 0), total - 1)
        }
        guard let start = Int64(startText), start < total else { return nil }
        let end = endText.isEmpty ? total - 1 : min(Int64(endText) ?? (total - 1), total - 1)
        guard end >= start else { return nil }
        return (start, end)
    }

    private static let bufferSize = 64 * 1024
}

/// Streams a `URLSessionDataTask`'s response into the local connection as bytes arrive, rather
/// than buffering the whole body in memory. Held alive by the task itself via `retain(_:)` for
/// as long as the transfer is in flight.
private final class ProxyStreamer: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {

    private let contentType: String
    private let headOnly: Bool
    private let onResponse: (Int, String, Int64?, String?, @escaping () -> Void) -> Void
    private let onData: (Data, @escaping () -> Void) -> Void
    private let onFinish: () -> Void
    private var retainedTask: URLSessionTask?

    init(
        contentType: String,
        headOnly: Bool,
        onResponse: @escaping (Int, String, Int64?, String?, @escaping () -> Void) -> Void,
        onData: @escaping (Data, @escaping () -> Void) -> Void,
        onFinish: @escaping () -> Void
    ) {
        self.contentType = contentType
        self.headOnly = headOnly
        self.onResponse = onResponse
        self.onData = onData
        self.onFinish = onFinish
    }

    func retain(_ task: URLSessionTask) {
        retainedTask = task
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let http = response as? HTTPURLResponse
        let status = http?.statusCode ?? 200
        let reason = status == 206 ? "Partial Content" : "OK"
        let length = response.expectedContentLength >= 0 ? response.expectedContentLength : nil
        let contentRange = http?.value(forHTTPHeaderField: "Content-Range")

        if headOnly {
            onResponse(status, reason, length, contentRange) { [weak self] in
                self?.onFinish()
            }
            completionHandler(.cancel)
            return
        }

        // Headers are sent once, before the first body chunk; hold the delegate queue here
        // until they are actually on the wire so a chunk cannot race ahead of them.
        let gate = DispatchSemaphore(value: 0)
        onResponse(status, reason, length, contentRange) { gate.signal() }
        gate.wait()
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        // Applies backpressure: the delegate queue (and therefore further didReceive calls)
        // blocks until the chunk has actually been handed to the socket.
        let gate = DispatchSemaphore(value: 0)
        onData(data) { gate.signal() }
        gate.wait()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        onFinish()
        retainedTask = nil
    }
}

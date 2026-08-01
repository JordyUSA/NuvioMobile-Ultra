import Foundation
import ComposeApp

/// DLNA/UPnP-AV SSDP discovery and SOAP control for iOS.
///
/// Mirrors `CastBridge.swift`'s "Swift does the I/O, Kotlin does the parsing" split: this file's
/// only job is moving bytes — UDP multicast datagrams for SSDP, HTTP GET/POST for SOAP — never
/// interpreting them. `DlnaProtocol.kt` on the Kotlin side owns every bit of XML/SOAP/DIDL-Lite
/// parsing and building, the same split `CastTranscoder.swift`/`CastMediaProber.ios.kt`
/// established for FFprobe.
///
/// Plain BSD sockets (`Darwin`) are used for the multicast SSDP part rather than
/// `Network.framework`'s multicast API: this app's Kotlin/Native interop instability so far has
/// been with Swift-generated completion-handler generics crossing into Kotlin, not with ordinary
/// C socket calls, so there is nothing to gain from the newer, less-documented API here.
///
/// Every callback into Kotlin is dispatched onto the main thread, the same discipline
/// `CastBridge.swift`'s `onMain` already enforces for the Cast SDK — `DlnaPlatform.ios.kt` pins
/// its own coroutine scope to `Dispatchers.Main` for exactly this reason, so both sides agree on
/// which thread ever touches the shared device/request state.
final class DlnaTransport: NSObject, DlnaTransportBridge {

    private static let multicastAddress = "239.255.255.250"
    private static let multicastPort: UInt16 = 1900

    /// `MX`: the ceiling, in seconds, on how long a device may wait before answering.
    private static let mxSeconds: Int32 = 3

    /// Kept above `mxSeconds` so the retransmits still leave a full window to answer in.
    private static let searchWindowSeconds: TimeInterval = 5
    private static let searchIntervalSeconds: TimeInterval = 10
    private static let searchAttempts = 3
    private static let searchAttemptGapSeconds: TimeInterval = 0.25

    /// `MediaRenderer:1` alone misses televisions that only answer `ssdp:all` reliably.
    private static let searchTargets = [
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "ssdp:all",
    ]

    private let queue = DispatchQueue(label: "dlna-transport", qos: .utility)
    private let notifyQueue = DispatchQueue(label: "dlna-notify", qos: .utility)
    private var searchWorkItem: DispatchWorkItem?

    /// Read from both queues — the active search runs on `queue`, the NOTIFY listener on
    /// `notifyQueue` — so it is guarded rather than left as a plain `Bool`.
    private let stateLock = NSLock()
    private var _discoveryActive = false
    private var discoveryActive: Bool {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _discoveryActive
        }
        set {
            stateLock.lock()
            defer { stateLock.unlock() }
            _discoveryActive = newValue
        }
    }

    @discardableResult
    static func install() -> DlnaTransport {
        let transport = DlnaTransport()
        DlnaBridgeRegistrationKt.registerDlnaTransportBridge(bridge: transport)
        return transport
    }

    // MARK: - DlnaTransportBridge

    func startDiscovery() {
        queue.async {
            guard !self.discoveryActive else { return }
            self.discoveryActive = true
            self.scheduleSearch()
            // Started only once the flag is set, or the listener's `while discoveryActive`
            // would see false and exit immediately. Passive listening runs alongside the active
            // search, not instead of it: a television switched on while the picker is open
            // announces itself rather than waiting for the next search round.
            self.notifyQueue.async { [weak self] in self?.listenForNotifications() }
        }
    }

    func stopDiscovery() {
        queue.async {
            self.discoveryActive = false
            self.searchWorkItem?.cancel()
            self.searchWorkItem = nil
        }
    }

    func httpGet(url: String, requestId: String) {
        performHttp(url: url, method: "GET", soapActionHeader: nil, body: nil, requestId: requestId)
    }

    func httpPost(url: String, soapActionHeader: String, body: String, requestId: String) {
        performHttp(url: url, method: "POST", soapActionHeader: soapActionHeader, body: body, requestId: requestId)
    }

    // MARK: - Discovery

    private func scheduleSearch() {
        guard discoveryActive else { return }
        performSearch()
        guard discoveryActive else { return }
        let work = DispatchWorkItem { [weak self] in self?.scheduleSearch() }
        searchWorkItem = work
        queue.asyncAfter(deadline: .now() + Self.searchIntervalSeconds, execute: work)
    }

    private func performSearch() {
        guard let socketFd = openSearchSocket() else { return }
        defer { close(socketFd) }

        var destination = sockaddr_in()
        destination.sin_family = sa_family_t(AF_INET)
        destination.sin_port = Self.multicastPort.bigEndian
        inet_pton(AF_INET, Self.multicastAddress, &destination.sin_addr)

        // SSDP rides on UDP multicast, which is lossy over Wi-Fi, and a single dropped datagram
        // hides a device for a whole search interval. Repeating each target is what the spec
        // expects of a control point and is the biggest factor in whether a TV turns up at all.
        for attempt in 0..<Self.searchAttempts {
            for target in Self.searchTargets {
                let request = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: \(Self.multicastAddress):\(Self.multicastPort)\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: \(Self.mxSeconds)\r\n" +
                    "ST: \(target)\r\n" +
                    "\r\n"
                let requestBytes = Array(request.utf8)
                _ = withUnsafePointer(to: &destination) { pointer -> Int in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                        sendto(socketFd, requestBytes, requestBytes.count, 0, sockaddrPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            if attempt < Self.searchAttempts - 1 {
                Thread.sleep(forTimeInterval: Self.searchAttemptGapSeconds)
            }
        }

        let deadline = Date().addingTimeInterval(Self.searchWindowSeconds)
        var buffer = [UInt8](repeating: 0, count: 8192)
        while Date() < deadline && discoveryActive {
            let received = recv(socketFd, &buffer, buffer.count, 0)
            if received > 0 {
                if let text = String(bytes: buffer[0..<received], encoding: .utf8) {
                    onMain { DlnaPlatform.shared.onSsdpResponse(raw: text) }
                }
                continue
            }
            // A negative result here is nearly always SO_RCVTIMEO expiring, which only means
            // "nothing yet". Devices answer at a random point inside the MX window precisely so
            // they don't all reply at once, so a quiet second is not the end of the window —
            // treating it as one used to discard most of the window and with it most of the
            // devices. Anything that isn't a timeout is a real error and does end the loop.
            if received < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR { break }
        }
        onMain { DlnaPlatform.shared.onSearchCycleCompleted() }
    }

    /// A plain UDP socket for the active search: SSDP search *responses* come back as ordinary
    /// unicast datagrams addressed to this socket's own ephemeral port, so it does not need to
    /// join the multicast group. Unsolicited `NOTIFY` announcements do, and are handled by
    /// `openNotifySocket()` below.
    private func openSearchSocket() -> Int32? {
        let socketFd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socketFd >= 0 else { return nil }

        // Short relative to the search window, so `stopDiscovery` is noticed promptly rather
        // than only once the window closes.
        var timeout = timeval(tv_sec: 1, tv_usec: 0)
        setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        var ttl: UInt8 = 4
        setsockopt(socketFd, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, socklen_t(MemoryLayout<UInt8>.size))

        return socketFd
    }

    // MARK: - Passive discovery (NOTIFY)

    private func listenForNotifications() {
        while discoveryActive {
            guard let socketFd = openNotifySocket() else {
                // Rebind after a failure rather than silently giving up on passive discovery
                // for the rest of the session.
                Thread.sleep(forTimeInterval: 5)
                continue
            }
            var buffer = [UInt8](repeating: 0, count: 8192)
            while discoveryActive {
                let received = recv(socketFd, &buffer, buffer.count, 0)
                if received > 0 {
                    if let text = String(bytes: buffer[0..<received], encoding: .utf8) {
                        onMain { DlnaPlatform.shared.onSsdpNotify(raw: text) }
                    }
                    continue
                }
                if received < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR { break }
            }
            close(socketFd)
        }
    }

    /// Bound to port 1900 and joined to the SSDP group, which is what unsolicited `NOTIFY`
    /// announcements are multicast to. `SO_REUSEPORT` matters because 1900 is shared with every
    /// other UPnP control point on the device.
    private func openNotifySocket() -> Int32? {
        let socketFd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socketFd >= 0 else { return nil }

        var reuse: Int32 = 1
        setsockopt(socketFd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(socketFd, SOL_SOCKET, SO_REUSEPORT, &reuse, socklen_t(MemoryLayout<Int32>.size))

        var timeout = timeval(tv_sec: 1, tv_usec: 0)
        setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = Self.multicastPort.bigEndian
        address.sin_addr.s_addr = in_addr_t(0) // INADDR_ANY
        // Darwin-qualified: this class derives from NSObject, which carries its own `bind`.
        let bound = withUnsafePointer(to: &address) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                Darwin.bind(socketFd, sockaddrPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound >= 0 else {
            close(socketFd)
            return nil
        }

        var request = ip_mreq()
        inet_pton(AF_INET, Self.multicastAddress, &request.imr_multiaddr)
        request.imr_interface.s_addr = in_addr_t(0) // INADDR_ANY: join on the default interface
        guard setsockopt(socketFd, IPPROTO_IP, IP_ADD_MEMBERSHIP, &request, socklen_t(MemoryLayout<ip_mreq>.size)) >= 0 else {
            close(socketFd)
            return nil
        }

        return socketFd
    }

    // MARK: - HTTP (SOAP)

    private func performHttp(url: String, method: String, soapActionHeader: String?, body: String?, requestId: String) {
        guard let requestUrl = URL(string: url) else {
            onMain { DlnaPlatform.shared.onHttpResult(requestId: requestId, success: false, body: nil, message: "Invalid URL") }
            return
        }
        var request = URLRequest(url: requestUrl, timeoutInterval: 5)
        request.httpMethod = method
        if let soapActionHeader {
            request.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type")
            request.setValue(soapActionHeader, forHTTPHeaderField: "SOAPACTION")
        }
        if let body {
            request.httpBody = body.data(using: .utf8)
        }

        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                self.onMain {
                    DlnaPlatform.shared.onHttpResult(requestId: requestId, success: false, body: nil, message: error.localizedDescription)
                }
                return
            }
            let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            // The status was previously discarded, so a renderer refusing an action with a 500
            // SOAP Fault came back as a success and the stream was reported as playing while
            // the television sat idle. The body is still passed through: the fault detail is in
            // it, and Kotlin does the parsing.
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200...299).contains(status) else {
                self.onMain {
                    DlnaPlatform.shared.onHttpResult(requestId: requestId, success: false, body: text, message: "HTTP \(status)")
                }
                return
            }
            self.onMain {
                DlnaPlatform.shared.onHttpResult(requestId: requestId, success: true, body: text, message: nil)
            }
        }.resume()
    }

    private func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }
}

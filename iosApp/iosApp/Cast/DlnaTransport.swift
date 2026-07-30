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
    private static let searchWindowSeconds: Int32 = 3
    private static let searchIntervalSeconds: TimeInterval = 10

    private let queue = DispatchQueue(label: "dlna-transport", qos: .utility)
    private var discoveryActive = false
    private var searchWorkItem: DispatchWorkItem?

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

        let request = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: \(Self.multicastAddress):\(Self.multicastPort)\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: \(Self.searchWindowSeconds)\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "\r\n"
        let requestBytes = Array(request.utf8)

        var destination = sockaddr_in()
        destination.sin_family = sa_family_t(AF_INET)
        destination.sin_port = Self.multicastPort.bigEndian
        inet_pton(AF_INET, Self.multicastAddress, &destination.sin_addr)

        _ = withUnsafePointer(to: &destination) { pointer -> Int in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                sendto(socketFd, requestBytes, requestBytes.count, 0, sockaddrPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        let deadline = Date().addingTimeInterval(TimeInterval(Self.searchWindowSeconds))
        var buffer = [UInt8](repeating: 0, count: 4096)
        while Date() < deadline {
            let received = recv(socketFd, &buffer, buffer.count, 0)
            guard received > 0 else { break }
            if let text = String(bytes: buffer[0..<received], encoding: .utf8) {
                onMain { DlnaPlatform.shared.onSsdpResponse(raw: text) }
            }
        }
        onMain { DlnaPlatform.shared.onSearchCycleCompleted() }
    }

    /// A plain UDP socket, not joined to the multicast group: SSDP search *responses* come back
    /// as ordinary unicast datagrams addressed to this socket's own ephemeral port, so nothing
    /// needs to be received from the multicast group itself — that would only matter for
    /// unsolicited `NOTIFY` announcements, which this app doesn't listen for.
    private func openSearchSocket() -> Int32? {
        let socketFd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socketFd >= 0 else { return nil }

        var timeout = timeval(tv_sec: 1, tv_usec: 0)
        setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        var ttl: UInt8 = 4
        setsockopt(socketFd, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, socklen_t(MemoryLayout<UInt8>.size))

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

        URLSession.shared.dataTask(with: request) { data, _, error in
            if let error {
                self.onMain {
                    DlnaPlatform.shared.onHttpResult(requestId: requestId, success: false, body: nil, message: error.localizedDescription)
                }
                return
            }
            let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            self.onMain {
                DlnaPlatform.shared.onHttpResult(requestId: requestId, success: true, body: text, message: nil)
            }
        }.resume()
    }

    private func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }
}

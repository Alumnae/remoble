import Foundation
import Network

private func remobleDebugLog(_ message: @autoclosure () -> String) {
#if DEBUG
    NSLog("%@", message())
#endif
}
import UIKit
import CommonCrypto
import CryptoKit
import Security

// Network.framework invokes these closures as @Sendable. Listener and connection
// mutations are confined to `queue`; API work is explicitly hopped to MainActor.
final class RemoHTTPServer: @unchecked Sendable {
    private enum TransactionState: String, Codable {
        case active
        case recovering
        case outcomeUnknown
    }

    private struct TransactionJournal: Codable {
        let readerID: String
        var readerName: String
        var eid: String?
        let clientID: String
        var aid: String?
        var state: TransactionState
        let startedAt: Date
        var updatedAt: Date
        var lastConfirmedAPDUHash: String?
        var inFlightAPDUHash: String?
        var lastRSSI: Int?
    }

    private struct ChannelKey: Hashable {
        let client: String
        let reader: String
        let aid: String
    }

    private struct CardAuthKey: Hashable {
        let client: String
        let readerID: UUID
    }

    private struct CardAuthState {
        var challenge: Data?
        var authorized = false
    }

    /// These mirror RemoCard's kotlinx-serialization request models. Decode
    /// endpoint payloads through Codable rather than relying on conditional
    /// casts from JSONSerialization's Foundation bridge.
    private struct OpenChannelRequest: Decodable {
        let aids: [String]
        let reader: String?
    }

    private struct APDURequest: Decodable {
        let apdu: String
        let aid: String?
        let reader: String?
    }

    private unowned let api: RemobleBridge
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "ee.nekoko.remoble.http")
    // Maps the RemoCard reader/AID handle to the real ISO 7816 logical channel
    // allocated on that eUICC. NekokoLPA2 deliberately sends channel-zero CLA
    // bytes to a remote server, so Remoble must apply the physical channel.
    private var channels: [ChannelKey: Int] = [:]
    private var cardAuthStates: [CardAuthKey: CardAuthState] = [:]
    private let cardAuthenticator = NineEsimCardAuthenticator()
    private var busyReaders = Set<UUID>()
    private var activeClientID: String?
    private var activeClientRequestCount = 0
    private var clientTransitionInProgress = false
    private var transactionJournals: [String: TransactionJournal]
    private var takeoverAttempts: [String: Int] = [:]
    private var lastOpenChannelDiagnostic: String?
    private static let transactionJournalKey = "http.transaction.journals.v1"
    /// RemoCard's compatibility set. Some client/runtime combinations encode
    /// the `aids` field in a Foundation shape that cannot be bridged back to a
    /// Swift string array. Falling back to the protocol's well-known set is
    /// preferable to issuing MANAGE CHANNEL with no SELECT candidates.
    private static let compatibilityAIDs = [
        "A06573746B6D65FFFF4953442D522031",
        "A06573746B6D65FFFF4953442D522030",
        "A0000005591010FFFFFFFF8900000100",
        "A0000005591010FFFFFFFF8900050500",
        "A0000005591010000000008900000300",
        "A0000005591010FFFFFFFF8900000177"
    ]

    init(api: RemobleBridge) {
        self.api = api
        self.transactionJournals = Self.loadTransactionJournals()
        // A process restart means any previously active operation lost its
        // transport channel. Preserve ownership but require reconnection.
        for key in transactionJournals.keys where transactionJournals[key]?.state == .active {
            transactionJournals[key]?.state = .recovering
            transactionJournals[key]?.updatedAt = Date()
        }
        persistTransactionJournals()
    }

    func start(port: UInt16) throws {
        guard listener == nil, let endpointPort = NWEndpoint.Port(rawValue: port) else { return }
        let listener = try NWListener(using: .tcp, on: endpointPort)
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.stateUpdateHandler = { state in
            if case let .failed(error) = state { NSLog("Remoble HTTP listener failed: %@", error.localizedDescription) }
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    func stop() {
        listener?.cancel()
        listener = nil
        channels.removeAll()
        cardAuthStates.removeAll()
        activeClientID = nil
    }

    @MainActor
    func invalidateChannels(for reader: BLEReader) {
        let key = reader.id.uuidString
        if var journal = transactionJournals[key] {
            journal.state = journal.inFlightAPDUHash == nil ? .recovering : .outcomeUnknown
            journal.updatedAt = Date()
            transactionJournals[key] = journal
            persistTransactionJournals()
        }
        removeChannels(for: api.readerName(reader))
        cardAuthStates.keys.filter { $0.readerID == reader.id }
            .forEach { cardAuthStates.removeValue(forKey: $0) }
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(connection, buffer: Data())
    }

    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1_048_576) { [weak self] data, _, complete, error in
            guard let self else { return }
            var accumulated = buffer
            if let data { accumulated += data }
            if let request = HTTPRequest.parse(accumulated) {
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    let backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Remoble HTTP request")
                    defer {
                        if backgroundTask != .invalid { UIApplication.shared.endBackgroundTask(backgroundTask) }
                    }
                    let response = await self.securedRoute(
                        request,
                        clientID: request.clientID ?? self.clientID(for: connection)
                    )
                    self.send(response, over: connection)
                }
            } else if complete || error != nil {
                self.send(.json(400, ["error": "Invalid HTTP request"]), over: connection)
            } else {
                self.receive(connection, buffer: accumulated)
            }
        }
    }

    private func send(_ response: HTTPResponse, over connection: NWConnection) {
        let reason: String
        switch response.status {
        case 200: reason = "OK"
        case 400: reason = "Bad Request"
        case 401: reason = "Unauthorized"
        case 409: reason = "Conflict"
        case 404: reason = "Not Found"
        default: reason = "Internal Server Error"
        }
        var headers = "HTTP/1.1 \(response.status) \(reason)\r\n"
        headers += "Content-Type: application/json; charset=utf-8\r\n"
        headers += "Content-Length: \(response.body.count)\r\n"
        headers += "Access-Control-Allow-Origin: *\r\n"
        headers += "Access-Control-Allow-Headers: Content-Type, Authorization, X-Remocard-Version, X-Remoble-Client\r\n"
        headers += "Connection: close\r\n\r\n"
        connection.send(content: Data(headers.utf8) + response.body, completion: .contentProcessed { _ in connection.cancel() })
    }

    @MainActor
    private func securedRoute(_ request: HTTPRequest, clientID: String) async -> HTTPResponse {
        // CORS preflight carries no credentials and no application data.
        if request.method == "OPTIONS" { return await route(request, clientID: clientID) }

        let password = api.httpPassword
        guard !password.isEmpty else {
            // NekokoLPA2 always wraps POST bodies in {"data": ...} when a
            // remote password is configured. Without the same local secret
            // this server cannot safely inspect reader/AID/APDU fields.
            if request.method == "POST",
               let wrapper = request.json as? [String: Any],
               wrapper["data"] is String {
                return .json(401, [
                    "error": "Encrypted RemoCard request received. Configure the same remote password in Remoble.",
                    "code": "password_required"
                ])
            }
            return await routeWithClientOwnership(request, clientID: clientID)
        }
        guard let authorization = request.headers["authorization"],
              Self.constantTimeEqual(authorization, "Bearer \(password)") else {
            // Keep authentication failures unencrypted: NekokoLPA2 uses the
            // HTTP status to report an incorrect password before decrypting.
            return .json(401, ["error": "Invalid password", "code": "invalid_password"])
        }
        // Audio-control payloads contain no card or profile data. Keep this
        // extension interoperable with another Remoble using ordinary JSON,
        // while still requiring the same bearer password when configured.
        if ["/play", "/stop", "/volume", "/timer", "/audio"].contains(request.path) {
            return await routeWithClientOwnership(request, clientID: clientID)
        }
        do {
            var authenticatedRequest = request
            if request.method == "POST" {
                guard let wrapper = request.json as? [String: Any],
                      let encrypted = wrapper["data"] as? String else {
                    throw RemoCryptoError.invalidEnvelope
                }
                let clearBody = try Self.crypt(encrypted, password: password, operation: CCOperation(kCCDecrypt))
                let decodedJSON = try JSONSerialization.jsonObject(with: clearBody)
                authenticatedRequest = request.replacingJSON(decodedJSON)
            }
            let response = await routeWithClientOwnership(authenticatedRequest, clientID: clientID)
            return try Self.encrypt(response, password: password)
        } catch {
            let response = HTTPResponse.json(400, ["error": error.localizedDescription])
            return (try? Self.encrypt(response, password: password)) ?? response
        }
    }

    @MainActor
    private func routeWithClientOwnership(_ request: HTTPRequest, clientID: String) async -> HTTPResponse {
        let channelCommands = ["/openChannel", "/sendApdu", "/sendRawApdu", "/closeChannel"]
        guard request.method == "POST", channelCommands.contains(request.path) else {
            // Discovery and capability checks never acquire or steal a card.
            return await route(request, clientID: clientID)
        }
        let requestedReader = (request.json as? [String: Any])?["reader"] as? String
        let readerID = api.reader(named: requestedReader)?.id
        return await withClientLease(clientID, readerID: readerID,
                                     closing: request.path == "/closeChannel") {
            await self.route(request, clientID: clientID)
        }
    }

    @MainActor
    private func route(_ request: HTTPRequest, clientID: String) async -> HTTPResponse {
        // Reader-specific commands reconnect their own BLE transport on demand.
        // Do not start every saved handshake for discovery-only requests such
        // as /listSlots: simultaneous NBLE challenges can starve one another.
        if request.method == "OPTIONS" { return .json(200, [:]) }
        if request.method == "GET", request.path == "/" {
            return .json(200, [
                "version": "2.1-remoble-ios",
                "deviceName": "Remoble iPhone",
                "supportedCommands": ["sendRawApdu", "sendApdu", "openChannel", "closeChannel", "listSlots", "listChannels", "transactionStatus", "play", "stop", "volume", "timer", "audio"],
                "encryptionRequired": api.httpPasswordConfigured,
                "sessionSupported": false
            ])
        }
        if request.method == "GET", request.path == "/listSlots" {
            let slots = api.availableReadersForHTTP.map {
                // A remembered reader remains a present slot. The first command
                // reconnects it on demand, matching Android Remoble semantics.
                ["name": api.readerName($0), "isPresent": true] as [String: Any]
            }
            return .json(200, ["slots": slots])
        }
        if request.method == "GET", request.path == "/listChannels" {
            let visible = channels.keys.filter { $0.client == clientID }
                .map { "\($0.reader):\($0.aid)" }.sorted()
            return .json(200, ["channels": visible])
        }
        if request.method == "GET", request.path == "/transactionStatus" {
            let entries: [[String: Any]] = transactionJournals.values.map { journal in
                var value: [String: Any] = [
                    "reader": journal.readerName,
                    "client": journal.clientID,
                    "state": journal.state.rawValue,
                    "startedAt": ISO8601DateFormatter().string(from: journal.startedAt),
                    "updatedAt": ISO8601DateFormatter().string(from: journal.updatedAt)
                ]
                if let eid = journal.eid { value["eid"] = eid }
                if let aid = journal.aid { value["aid"] = aid }
                if let rssi = journal.lastRSSI { value["rssi"] = rssi }
                if let hash = journal.lastConfirmedAPDUHash { value["lastConfirmedAPDU"] = hash }
                if let hash = journal.inFlightAPDUHash { value["uncertainAPDU"] = hash }
                return value
            }
            return .json(200, ["transactions": entries])
        }
        if request.method == "GET", request.path == "/audio" {
            return .json(200, api.remoteAudioState())
        }
        if request.method == "POST", ["/play", "/stop", "/volume", "/timer"].contains(request.path) {
            let audioBody = request.json as? [String: Any] ?? [:]
            do {
                switch request.path {
                case "/play":
                    try api.remotePlay(sound: audioBody["sound"] as? String)
                case "/stop":
                    try api.remoteStop()
                case "/volume":
                    guard let number = audioBody["value"] as? NSNumber else {
                        return .json(400, ["error": "Numeric volume value required"])
                    }
                    try api.remoteSetVolume(number.doubleValue)
                case "/timer":
                    let minutes = (audioBody["minutes"] as? NSNumber)?.intValue
                    try api.remoteSetTimer(audioBody["minutes"] is NSNull ? nil : minutes)
                default: break
                }
                return .json(200, api.remoteAudioState())
            } catch {
                return .json(400, ["error": error.localizedDescription])
            }
        }

        guard let body = request.json as? [String: Any] else {
            return .json(400, ["error": "JSON body required"])
        }

        do {
            switch (request.method, request.path) {
            case ("POST", "/openChannel"):
                let requestStartedAt = Date()
                let decoded = decodeRequest(OpenChannelRequest.self, from: body)
                guard let reader = api.reader(named: decoded?.reader ?? body["reader"] as? String) else {
                    return .json(404, ["error": "Reader not found"])
                }
                if let journal = transactionJournals[reader.id.uuidString] {
                    guard journal.clientID == clientID else {
                        return transactionConflict(journal)
                    }
                    if journal.state == .outcomeUnknown {
                        return .json(409, [
                            "error": "The previous APDU outcome is unknown. Close the channel and restart the secure-card operation; Remoble will not replay it automatically.",
                            "code": "outcome_unknown",
                            "reader": api.readerName(reader)
                        ])
                    }
                }
                let ready = await api.waitUntilReadyForHTTP(reader)
                if !ready {
                    // NekokoLPA2 treats success:false as a terminal channel
                    // open failure unless it is already in a refresh flow. Do
                    // not return false merely because this same HTTP request
                    // woke and authenticated the reader: when it becomes ready
                    // within the deadline, continue directly with the APDUs.
                    return .json(200, [
                        "success": false,
                        "state": api.isAuthenticationBlocked(reader)
                            ? "pin_required" : (ready ? "ready" : "reconnecting"),
                        "retryAfterMs": 1000
                    ])
                }
                if transactionJournals[reader.id.uuidString] == nil,
                   let rssi = await api.preflightRSSI(for: reader), rssi < api.minimumRSSI {
                    return .json(409, [
                        "error": "BLE signal is too weak to start a new secure-card transaction",
                        "code": "signal_too_weak",
                        "rssi": rssi,
                        "minimumRSSI": api.minimumRSSI
                    ])
                }
                // Preserve the exact spelling received from NekokoLPA2. The
                // original RemoCard server returns that same matched string,
                // which the client then sends back as `aid` to /sendApdu.
                let aids = decoded?.aids.isEmpty == false
                    ? decoded!.aids
                    : requestedAIDs(from: body)
                remobleDebugLog("Remoble openChannel using \(aids.count) AID candidates")
                var opened = try await withReaderLock(reader) {
                    try await openFirst(aids: aids, reader: reader, clientID: clientID)
                }
                // A rejected AID starts one physical off/on recovery. Keep
                // this HTTP request alive long enough to consume that recovery
                // and retry once: NekokoLPA2 does not retry a plain
                // `success:false` result outside its proactive-refresh state.
                // Reserve time for the second APDU sequence so the response
                // remains below RemoCardAdapter's 15-second POST timeout.
                if opened == nil, api.isSettling(reader) {
                    let remaining = 12.5 - Date().timeIntervalSince(requestStartedAt)
                    if remaining > 2.0 {
                        let recovered = await api.waitUntilReadyForHTTP(
                            reader, timeout: min(5.0, remaining - 2.0))
                        if recovered {
                            opened = try await withReaderLock(reader) {
                                try await openFirst(aids: aids, reader: reader, clientID: clientID)
                            }
                        }
                    }
                }
                if let opened {
                    api.markOpenChannelSucceeded(for: reader)
                    channels[channelKey(client: clientID, reader: api.readerName(reader), aid: opened.aid)] = opened.channel
                    cardAuthStates[CardAuthKey(client: clientID, readerID: reader.id)] = CardAuthState()
                    beginOrResumeTransaction(reader: reader, clientID: clientID, aid: opened.aid)
                    return .json(200, ["success": true, "aid": opened.aid, "reader": api.readerName(reader)])
                }
                return .json(200, [
                    "success": false,
                    "state": api.isAuthenticationBlocked(reader)
                        ? "pin_required" : (api.isSettling(reader) ? "reconnecting" : "aid_rejected"),
                    "detail": lastOpenChannelDiagnostic
                        ?? api.lastError
                        ?? "No provided application identifier was accepted"
                ])

            case ("POST", "/sendApdu"):
                guard let decoded = apduRequest(from: body) else {
                    return .json(400, invalidAPDURequestDetail(body))
                }
                guard let reader = api.reader(named: decoded.reader) else { return .json(404, ["error": "Reader not found"]) }
                if let journal = transactionJournals[reader.id.uuidString], journal.state == .outcomeUnknown {
                    return .json(409, ["error": "Previous APDU outcome is unknown", "code": "outcome_unknown"])
                }
                api.wakeReaderForRemoteAccess(reader)
                let aid = decoded.aid ?? ""
                guard let logicalChannel = channels[channelKey(client: clientID, reader: api.readerName(reader), aid: aid)] else {
                    return .json(400, [
                        "error": "Channel not found. Call openChannel first.",
                        "reader": api.readerName(reader),
                        "aid": aid
                    ])
                }
                let command = try Data(hex: decoded.apdu)
                let response = try await transmitJournaled(
                    command, reader: reader, clientID: clientID, aid: aid,
                    logicalChannel: logicalChannel)
                return .json(200, ["response": response.hex])

            case ("POST", "/sendRawApdu"):
                guard let decoded = apduRequest(from: body) else {
                    return .json(400, invalidAPDURequestDetail(body))
                }
                guard let reader = api.reader(named: decoded.reader) else { return .json(404, ["error": "Reader not found"]) }
                if let journal = transactionJournals[reader.id.uuidString], journal.state == .outcomeUnknown {
                    return .json(409, ["error": "Previous APDU outcome is unknown", "code": "outcome_unknown"])
                }
                api.wakeReaderForRemoteAccess(reader)
                let command = try Data(hex: decoded.apdu)
                beginOrResumeTransaction(reader: reader, clientID: clientID, aid: nil)
                let response = try await transmitJournaled(
                    command, reader: reader, clientID: clientID, aid: nil,
                    logicalChannel: nil)
                return .json(200, ["response": response.hex])

            case ("POST", "/closeChannel"):
                let decoded = decodeRequest(OpenChannelRequest.self, from: body)
                let requestedReader = decoded?.reader ?? body["reader"] as? String
                    ?? api.availableReadersForHTTP.first.map(api.readerName) ?? ""
                let resolvedTarget = api.reader(named: requestedReader)
                let reader = resolvedTarget.map(api.readerName) ?? requestedReader
                let aids = decoded?.aids ?? stringArray(body["aids"])
                let canonicalAIDs = Set(aids.map(canonicalAID))
                let keys = channels.keys.filter {
                    $0.client == clientID && $0.reader == reader
                        && (canonicalAIDs.isEmpty || canonicalAIDs.contains($0.aid))
                }
                let logicalChannels = Set(keys.compactMap { channels.removeValue(forKey: $0) }).filter { $0 > 0 }
                if let target = resolvedTarget {
                    api.wakeReaderForRemoteAccess(target)
                    try await withReaderLock(target) {
                        for channel in logicalChannels {
                            _ = try? await transmitWithChaining(
                                Data([0x00, 0x70, 0x80, UInt8(channel), 0x00]), reader: target)
                        }
                    }
                    cardAuthStates.removeValue(forKey: CardAuthKey(client: clientID, readerID: target.id))
                    clearTransaction(reader: target, clientID: clientID)
                } else {
                    clearTransactions(named: reader, clientID: clientID)
                }
                return .json(200, ["success": true])

            default:
                return .json(404, ["error": "Not found"])
            }
        } catch {
            return .json(500, ["error": error.localizedDescription])
        }
    }

    @MainActor
    private func openFirst(aids: [String], reader: BLEReader,
                           clientID: String) async throws -> (aid: String, channel: Int)? {
        lastOpenChannelDiagnostic = nil
        let readerName = api.readerName(reader)
        var selectFailures: [String] = []
        for aid in aids {
            let key = channelKey(client: clientID, reader: readerName, aid: aid)
            if let channel = channels[key] { return (aid, channel) }
        }

        // RemoCardAdapter announces ensureSingleChannel but its remote
        // cleanup is only a short delay; it cannot close a channel whose
        // number exists solely inside this server. Reclaim only this client's
        // previous mappings before opening a different session. Cross-client
        // ownership remains protected by withClientLease.
        let previousKeys = channels.keys.filter {
            $0.client == clientID && $0.reader == readerName
        }
        if !previousKeys.isEmpty {
            let previousChannels = Set(previousKeys.compactMap { channels.removeValue(forKey: $0) })
            remobleDebugLog("Remoble reclaiming same-client channels \(previousChannels.sorted())")
        }

        // NekokoLPA2's BLE path enables ensureSingleChannel by default. A BLE
        // disconnect can erase our HTTP mapping without closing the physical
        // ISO 7816 channel, so clear the card's three allocatable channels
        // before starting a fresh session. Never do this while a tracked
        // channel is still owned by a live HTTP transaction.
        if !channels.keys.contains(where: { $0.reader == readerName }) {
            for channel in 1...3 {
                let cleanup = try? await transmitWithChaining(
                    Data([0x00, 0x70, 0x80, UInt8(channel), 0x00]),
                    reader: reader)
                remobleDebugLog("Remoble cleanup channel \(channel) -> \(cleanup?.hex ?? "transport-error")")
            }
        }

        // Match NekokoLPA2's native BLE adapters: announce terminal capability,
        // then allocate a real logical channel. Older cards that reject MANAGE
        // CHANNEL fall back to the basic channel.
        let terminalCapability = try? await transmitWithChaining(
            try Data(hex: "80AA00000AA908810082010183010700"), reader: reader)
        remobleDebugLog("Remoble terminal capability -> \(terminalCapability?.hex ?? "transport-error")")
        var logicalChannel = 0
        // B1 follows the ISO correction flow used by NekokoLPA2: request with
        // Le=00, accept 6C01, then resend with Le=01.
        let manage = try await transmitWithChaining(Data([0x00, 0x70, 0x00, 0x00, 0x00]), reader: reader)
        remobleDebugLog("Remoble manage channel -> \(manage.hex)")
        if manage.count >= 3,
           manage[manage.count - 2] == 0x90, manage[manage.count - 1] == 0x00,
           let allocated = manage.first, allocated > 0, allocated < 20 {
            logicalChannel = Int(allocated)
        } else if channels.keys.contains(where: { $0.reader == readerName }) {
            // Never put a second client on the shared basic channel while
            // another logical channel for this card is active.
            lastOpenChannelDiagnostic = "MANAGE CHANNEL rejected while another client owns the reader: \(manage.hex)"
            return nil
        } else {
            // Match NekokoLPA2's BaseAdapter: cards that reject MANAGE
            // CHANNEL may still expose the application on basic channel 0.
            lastOpenChannelDiagnostic = "MANAGE CHANNEL fallback to basic channel: \(manage.hex)"
        }

        for aid in aids {
            let response = try await transmitWithChaining(
                BLEProtocolCodec.selectAid(try Data(hex: aid)),
                reader: reader,
                logicalChannel: logicalChannel)
            remobleDebugLog("Remoble SELECT on channel \(logicalChannel) -> \(response.hex)")
            guard response.count >= 2 else {
                selectFailures.append("\(aid.uppercased()):truncated")
                continue
            }
            let sw1 = response[response.count - 2], sw2 = response[response.count - 1]
            if (sw1 == 0x90 && sw2 == 0x00) || sw1 == 0x61 || sw1 == 0x9F {
                lastOpenChannelDiagnostic = nil
                return (aid, logicalChannel)
            }
            selectFailures.append(String(format: "%@:%02X%02X", aid.uppercased(), sw1, sw2))
            if sw1 == 0x91
                || (sw1 == 0x69 && (sw2 == 0x85 || sw2 == 0x99))
                || (sw1 == 0x68 && sw2 == 0x81) {
                if logicalChannel > 0 {
                    _ = try? await transmitWithChaining(
                        Data([0x00, 0x70, 0x80, UInt8(logicalChannel), 0x00]), reader: reader)
                }
                lastOpenChannelDiagnostic = "SELECT temporarily unavailable: \(selectFailures.joined(separator: ", "))"
                api.beginRecovery(for: reader)
                return nil
            }
        }
        if logicalChannel > 0 {
            _ = try? await transmitWithChaining(
                Data([0x00, 0x70, 0x80, UInt8(logicalChannel), 0x00]), reader: reader)
        }
        if !channels.keys.contains(where: { $0.reader == readerName }) {
            removeChannels(for: readerName)
            // Schedule one physical retick for this entire retry generation.
            // The current HTTP call returns promptly; later Nekoko attempts
            // observe that same recovery instead of launching nine more.
            api.requestOpenChannelRecovery(for: reader)
        }
        lastOpenChannelDiagnostic = selectFailures.isEmpty
            ? "No AID was provided"
            : "SELECT rejected: \(selectFailures.joined(separator: ", "))"
        return nil
    }

    @MainActor
    private func transmitWithChaining(_ command: Data, reader: BLEReader,
                                      logicalChannel: Int? = nil) async throws -> Data {
        guard !command.isEmpty else { throw RemobleError.message("Empty APDU") }
        var wireCommand = command
        if let logicalChannel { wireCommand[0] = try mappedCLA(wireCommand[0], channel: logicalChannel) }
        var response = try await api.transmit(wireCommand, reader: reader)
        guard response.count >= 2 else { throw RemobleError.message("Truncated APDU response") }
        var output = Data(response.dropLast(2))
        var sw1 = response[response.count - 2], sw2 = response[response.count - 1]
        while sw1 == 0x61 {
            var getResponse = Data([command[0], 0xC0, 0x00, 0x00, sw2])
            if let logicalChannel { getResponse[0] = try mappedCLA(getResponse[0], channel: logicalChannel) }
            response = try await api.transmit(getResponse, reader: reader)
            guard response.count >= 2 else { throw RemobleError.message("Truncated GET RESPONSE") }
            output += response.dropLast(2)
            sw1 = response[response.count - 2]; sw2 = response[response.count - 1]
        }
        if sw1 == 0x6C, !command.isEmpty {
            var corrected = command
            corrected[corrected.count - 1] = sw2
            return try await transmitWithChaining(corrected, reader: reader, logicalChannel: logicalChannel)
        }
        output += Data([sw1, sw2])
        if let eid = extractEID(command: command, response: output) {
            api.recordEID(eid, for: reader)
        }
        return output
    }

    /// Learns the hardware identity opportunistically from the standard
    /// ES10b GET EID exchange already requested by NekokoLPA2. No additional
    /// APDU is injected into an active download/channel.
    private func extractEID(command: Data, response: Data) -> String? {
        let getEIDSuffix = Data([0xE2, 0x91, 0x00, 0x06, 0xBF, 0x3E, 0x03, 0x5C, 0x01, 0x5A])
        guard command.count == 11, Data(command.dropFirst()) == getEIDSuffix,
              response.count >= 20,
              response.suffix(2) == Data([0x90, 0x00]) else { return nil }
        let body = response.dropLast(2)
        guard body.count >= 18 else { return nil }
        for index in body.startIndex..<(body.endIndex - 17) where body[index] == 0x5A {
            let lengthIndex = body.index(after: index)
            guard body[lengthIndex] == 0x10 else { continue }
            let valueStart = body.index(after: lengthIndex)
            let valueEnd = body.index(valueStart, offsetBy: 16)
            guard valueEnd <= body.endIndex else { continue }
            return Data(body[valueStart..<valueEnd]).map { String(format: "%02X", $0) }.joined()
        }
        return nil
    }

    @MainActor
    private func transmitJournaled(_ command: Data, reader: BLEReader, clientID: String,
                                   aid: String?, logicalChannel: Int?) async throws -> Data {
        if let journal = transactionJournals[reader.id.uuidString] {
            guard journal.clientID == clientID else {
                throw RemobleError.message("Reader is owned by another client")
            }
            guard journal.state != .outcomeUnknown else {
                throw RemobleError.message("Previous APDU outcome is unknown; refusing automatic replay")
            }
        } else {
            beginOrResumeTransaction(reader: reader, clientID: clientID, aid: aid)
        }

        return try await withReaderLock(reader) {
            let hash = Self.apduHash(command)
            markAPDUInFlight(hash, reader: reader)
            do {
                try await authorizeCardIfNeeded(
                    before: command,
                    reader: reader,
                    clientID: clientID,
                    logicalChannel: logicalChannel)
                let response = try await transmitWithChaining(
                    command, reader: reader, logicalChannel: logicalChannel)
                observeCardAuthorization(
                    command: command,
                    response: response,
                    reader: reader,
                    clientID: clientID)
                markAPDUConfirmed(hash, reader: reader)
                return response
            } catch {
                markAPDUOutcomeUnknown(hash, reader: reader)
                throw error
            }
        }
    }

    /// Transparently supplies the card-level BF71 exchange used by affected
    /// B0 devices. It is deliberately performed here—not in /openChannel—
    /// because both EID and the one-time challenge only exist after SELECT.
    @MainActor
    private func authorizeCardIfNeeded(
        before command: Data,
        reader: BLEReader,
        clientID: String,
        logicalChannel: Int?
    ) async throws {
        guard Self.apduPayload(command).map({ Self.hasTag($0, 0xBF, 0x38) }) == true else {
            return
        }

        let key = CardAuthKey(client: clientID, readerID: reader.id)
        var state = cardAuthStates[key] ?? CardAuthState()
        if state.authorized {
            state.authorized = false
            cardAuthStates[key] = state
            return
        }
        guard let eid = api.eid(for: reader),
              NineEsimCardAuthenticator.maySupportAuthorization(
                eid: eid, readerName: reader.name) else {
            return
        }
        let required = NineEsimCardAuthenticator.requiresAuthorization(eid: eid)
        guard let challenge = state.challenge else {
            if required {
                throw RemobleError.message(
                    "Card authorization challenge is missing; restart the download session")
            }
            return
        }

        // A challenge is single-use. Clear it before any network or APDU
        // operation so an HTTP retry can never replay BF71.
        state.challenge = nil
        cardAuthStates[key] = state

        do {
            guard let request = try await cardAuthenticator.request(
                eid: eid, challenge: challenge) else {
                if required {
                    throw RemobleError.message("No authorization key is available for this card")
                }
                remobleDebugLog("Remoble has no optional card authorization key")
                return
            }
            var apdu = Data([0x81, 0xE2, 0x91, 0x00, UInt8(request.count)])
            apdu += request
            let response = try await transmitWithChaining(
                apdu, reader: reader, logicalChannel: logicalChannel)
            guard Self.isSuccessResponse(response) else {
                throw RemobleError.message(
                    "Card BF71 authorization failed (SW=\(response.suffix(2).hex.uppercased()))")
            }
            remobleDebugLog("Remoble card authorization succeeded")
        } catch {
            if required { throw error }
            remobleDebugLog("Remoble optional card authorization skipped: \(error.localizedDescription)")
        }
    }

    @MainActor
    private func observeCardAuthorization(
        command: Data,
        response: Data,
        reader: BLEReader,
        clientID: String
    ) {
        guard let payload = Self.apduPayload(command) else { return }
        let key = CardAuthKey(client: clientID, readerID: reader.id)
        var state = cardAuthStates[key] ?? CardAuthState()

        if Self.hasTag(payload, 0xBF, 0x2E) {
            state.challenge = Self.extractChallenge(from: response)
            state.authorized = false
        } else if Self.hasTag(payload, 0xBF, 0x71) {
            if Self.isSuccessResponse(response) {
                // A newer client already performed BF71. Do not inject it a
                // second time when the following BF38 arrives.
                state.challenge = nil
                state.authorized = true
            }
        } else if Self.hasTag(payload, 0xBF, 0x38) {
            state.challenge = nil
            state.authorized = false
        }
        cardAuthStates[key] = state
    }

    private static func apduPayload(_ command: Data) -> Data? {
        guard command.count > 5 else { return nil }
        let firstLength = Int(command[4])
        if firstLength > 0 {
            guard command.count >= 5 + firstLength else { return nil }
            return command.subdata(in: 5..<(5 + firstLength))
        }
        guard command.count >= 7 else { return nil }
        let extendedLength = (Int(command[5]) << 8) | Int(command[6])
        guard extendedLength > 0, command.count >= 7 + extendedLength else { return nil }
        return command.subdata(in: 7..<(7 + extendedLength))
    }

    private static func hasTag(_ payload: Data, _ first: UInt8, _ second: UInt8) -> Bool {
        payload.count >= 2 && payload[0] == first && payload[1] == second
    }

    private static func extractChallenge(from response: Data) -> Data? {
        guard isSuccessResponse(response), response.count >= 22 else { return nil }
        let body = Data(response.dropLast(2))
        guard hasTag(body, 0xBF, 0x2E) else { return nil }

        // BF2E may use either a short or long-form BER length. Its first child
        // is 80 10 followed by the 16-byte one-time challenge.
        var index = 2
        guard index < body.count else { return nil }
        let lengthByte = Int(body[index]); index += 1
        if lengthByte & 0x80 != 0 {
            let count = lengthByte & 0x7F
            guard count > 0, count <= 2, index + count <= body.count else { return nil }
            index += count
        }
        guard index + 18 <= body.count,
              body[index] == 0x80,
              body[index + 1] == 0x10 else { return nil }
        return body.subdata(in: (index + 2)..<(index + 18))
    }

    private static func isSuccessResponse(_ response: Data) -> Bool {
        response.count >= 2 && response.suffix(2) == Data([0x90, 0x00])
    }

    @MainActor
    private func beginOrResumeTransaction(reader: BLEReader, clientID: String, aid: String?) {
        let key = reader.id.uuidString
        if var current = transactionJournals[key] {
            guard current.clientID == clientID, current.state != .outcomeUnknown else { return }
            current.readerName = api.readerName(reader)
            current.eid = api.eid(for: reader) ?? current.eid
            current.aid = aid ?? current.aid
            current.state = .active
            current.updatedAt = Date()
            current.lastRSSI = api.rssi(for: reader)
            transactionJournals[key] = current
        } else {
            transactionJournals[key] = TransactionJournal(
                readerID: key,
                readerName: api.readerName(reader),
                eid: api.eid(for: reader),
                clientID: clientID,
                aid: aid,
                state: .active,
                startedAt: Date(),
                updatedAt: Date(),
                lastConfirmedAPDUHash: nil,
                inFlightAPDUHash: nil,
                lastRSSI: api.rssi(for: reader)
            )
        }
        persistTransactionJournals()
    }

    @MainActor
    private func markAPDUInFlight(_ hash: String, reader: BLEReader) {
        let key = reader.id.uuidString
        guard var journal = transactionJournals[key] else { return }
        journal.inFlightAPDUHash = hash
        journal.updatedAt = Date()
        journal.lastRSSI = api.rssi(for: reader)
        transactionJournals[key] = journal
        persistTransactionJournals()
    }

    @MainActor
    private func markAPDUConfirmed(_ hash: String, reader: BLEReader) {
        let key = reader.id.uuidString
        guard var journal = transactionJournals[key] else { return }
        journal.lastConfirmedAPDUHash = hash
        journal.inFlightAPDUHash = nil
        journal.state = .active
        journal.updatedAt = Date()
        journal.lastRSSI = api.rssi(for: reader)
        journal.eid = api.eid(for: reader) ?? journal.eid
        transactionJournals[key] = journal
        persistTransactionJournals()
    }

    @MainActor
    private func markAPDUOutcomeUnknown(_ hash: String, reader: BLEReader) {
        let key = reader.id.uuidString
        guard var journal = transactionJournals[key] else { return }
        journal.inFlightAPDUHash = hash
        journal.state = .outcomeUnknown
        journal.updatedAt = Date()
        journal.lastRSSI = api.rssi(for: reader)
        transactionJournals[key] = journal
        persistTransactionJournals()
    }

    @MainActor
    private func clearTransaction(reader: BLEReader, clientID: String) {
        let key = reader.id.uuidString
        guard transactionJournals[key]?.clientID == clientID else { return }
        transactionJournals.removeValue(forKey: key)
        persistTransactionJournals()
    }

    @MainActor
    private func clearTransactions(named readerName: String, clientID: String) {
        transactionJournals = transactionJournals.filter {
            !($0.value.clientID == clientID && $0.value.readerName == readerName)
        }
        persistTransactionJournals()
    }

    private func transactionConflict(_ journal: TransactionJournal) -> HTTPResponse {
        .json(409, [
            "error": "Reader is reserved by another client's secure-card transaction",
            "code": "reader_busy",
            "state": journal.state.rawValue
        ])
    }

    private static func apduHash(_ command: Data) -> String {
        SHA256.hash(data: command).prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    private static func loadTransactionJournals() -> [String: TransactionJournal] {
        guard let data = UserDefaults.standard.data(forKey: transactionJournalKey) else { return [:] }
        return (try? JSONDecoder().decode([String: TransactionJournal].self, from: data)) ?? [:]
    }

    private func persistTransactionJournals() {
        UserDefaults.standard.set(
            try? JSONEncoder().encode(transactionJournals),
            forKey: Self.transactionJournalKey)
    }

    private func channelKey(client: String, reader: String, aid: String) -> ChannelKey {
        ChannelKey(client: client, reader: reader, aid: canonicalAID(aid))
    }

    private func decodeRequest<T: Decodable>(_ type: T.Type, from object: Any) -> T? {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private func apduRequest(from body: [String: Any]) -> APDURequest? {
        if let decoded = decodeRequest(APDURequest.self, from: body) { return decoded }
        guard let apdu = stringField("apdu", in: body), !apdu.isEmpty else { return nil }
        return APDURequest(
            apdu: apdu,
            aid: stringField("aid", in: body),
            reader: stringField("reader", in: body)
        )
    }

    private func stringField(_ name: String, in body: [String: Any]) -> String? {
        guard let value = body.first(where: {
            $0.key.caseInsensitiveCompare(name) == .orderedSame
        })?.value else { return nil }
        if let string = value as? String { return string }
        if let string = value as? NSString { return string as String }
        // Keep compatibility with a byte-array APDU without changing the V2
        // contract used by the official adapter.
        if let bytes = value as? [NSNumber] {
            return bytes.map { String(format: "%02X", $0.uint8Value) }.joined()
        }
        if let bytes = value as? [Any], bytes.allSatisfy({ $0 is NSNumber }) {
            return bytes.compactMap { $0 as? NSNumber }
                .map { String(format: "%02X", $0.uint8Value) }.joined()
        }
        return nil
    }

    private func canonicalAID(_ aid: String) -> String {
        var result = aid.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: " ", with: "")
            .uppercased()
        if result.hasPrefix("0X") { result.removeFirst(2) }
        return result
    }

    private func invalidAPDURequestDetail(_ body: [String: Any]) -> [String: Any] {
        let fields = Dictionary(uniqueKeysWithValues: body.map {
            ($0.key, String(reflecting: type(of: $0.value)))
        })
        return [
            "error": "Invalid APDU request",
            "fields": fields,
            "required": ["reader", "aid", "apdu"]
        ]
    }

    /// JSONSerialization may expose an encrypted payload's array as NSArray
    /// or [Any] rather than a covariant [String]. Normalize every supported
    /// Foundation representation instead of silently treating a valid AID
    /// list as empty.
    private func stringArray(_ value: Any?) -> [String] {
        if let strings = value as? [String] { return strings }
        if let values = value as? [Any] {
            return values.compactMap { element in
                if let string = element as? String { return string }
                if let string = element as? NSString { return string as String }
                return nil
            }
        }
        if let values = value as? NSArray {
            return values.compactMap { element in
                if let string = element as? String { return string }
                if let string = element as? NSString { return string as String }
                return nil
            }
        }
        if let string = value as? String {
            // Accept both a single AID and clients that serialise the list as
            // a JSON string instead of a JSON array.
            if let data = string.data(using: .utf8),
               let decoded = try? JSONSerialization.jsonObject(with: data),
               !(decoded is String) {
                let nested = stringArray(decoded)
                if !nested.isEmpty { return nested }
            }
            return string.split(separator: ",").map {
                String($0).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        return []
    }

    /// Extract the protocol field without relying on one concrete Foundation
    /// bridge representation. If it is still unavailable, use the same
    /// compatibility AIDs as the Android RemoCard implementation.
    private func requestedAIDs(from body: [String: Any]) -> [String] {
        var raw: [String] = []
        for (key, value) in body where key.caseInsensitiveCompare("aids") == .orderedSame {
            raw.append(contentsOf: stringArray(value))
        }
        if raw.isEmpty {
            for containerKey in ["request", "payload", "body"] {
                if let nested = body[containerKey] as? [String: Any] {
                    for (key, value) in nested where key.caseInsensitiveCompare("aids") == .orderedSame {
                        raw.append(contentsOf: stringArray(value))
                    }
                } else if let nested = body[containerKey] as? NSDictionary {
                    for (key, value) in nested {
                        if let key = key as? String,
                           key.caseInsensitiveCompare("aids") == .orderedSame {
                            raw.append(contentsOf: stringArray(value))
                        }
                    }
                }
            }
        }

        var seen = Set<String>()
        let normalized = raw.compactMap { candidate -> String? in
            var aid = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: " ", with: "")
                .uppercased()
            if aid.hasPrefix("0X") { aid.removeFirst(2) }
            guard !aid.isEmpty, aid.count.isMultiple(of: 2),
                  aid.unicodeScalars.allSatisfy({
                      (48...57).contains($0.value) || (65...70).contains($0.value)
                  }), seen.insert(aid).inserted else { return nil }
            return aid
        }
        return normalized.isEmpty ? Self.compatibilityAIDs : normalized
    }

    private func removeChannels(for reader: String) {
        channels.keys.filter { $0.reader == reader }.forEach { channels.removeValue(forKey: $0) }
    }

    /// Same-client requests may remain concurrent (and different readers can
    /// work in parallel). Ownership lasts from openChannel through closeChannel.
    /// A second client can never revoke a live channel in the gaps between the
    /// many HTTP APDUs that form one profile download.
    @MainActor
    private func withClientLease(
        _ clientID: String,
        readerID: UUID?,
        closing: Bool,
        operation: () async -> HTTPResponse
    ) async -> HTTPResponse {
        while true {
            while clientTransitionInProgress {
                try? await Task<Never, Never>.sleep(nanoseconds: 10_000_000)
            }

            if let readerID, let journal = transactionJournals[readerID.uuidString],
               journal.clientID != clientID {
                let attemptKey = "\(readerID.uuidString)|\(clientID)"
                let hasLiveChannel = channels.keys.contains {
                    $0.reader == journal.readerName && $0.client == journal.clientID
                }
                let canTakeOver = !hasLiveChannel
                    && !busyReaders.contains(readerID)
                    && activeClientRequestCount == 0
                    && (journal.state != .active || Date().timeIntervalSince(journal.updatedAt) >= 30)
                if canTakeOver {
                    let attempts = takeoverAttempts[attemptKey, default: 0] + 1
                    takeoverAttempts[attemptKey] = attempts
                    if attempts >= 10 {
                        transactionJournals.removeValue(forKey: readerID.uuidString)
                        channels.keys.filter { $0.reader == journal.readerName }
                            .forEach { channels.removeValue(forKey: $0) }
                        takeoverAttempts.removeValue(forKey: attemptKey)
                        if activeClientID == journal.clientID { activeClientID = nil }
                        persistTransactionJournals()
                        continue
                    }
                    return .json(409, [
                        "error": "Reader is reserved by another client's stale secure-card transaction",
                        "code": "reader_busy",
                        "state": journal.state.rawValue,
                        "overrideAttempt": attempts,
                        "attemptsRemaining": 10 - attempts
                    ])
                }
                takeoverAttempts.removeValue(forKey: attemptKey)
                return transactionConflict(journal)
            }

            if activeClientID == nil || activeClientID == clientID {
                activeClientID = clientID
                activeClientRequestCount += 1
                break
            }

            if channels.keys.contains(where: { $0.client == activeClientID }) {
                return .json(409, [
                    "error": "Reader is busy with another client's active download",
                    "code": "reader_busy"
                ])
            }

            clientTransitionInProgress = true
            while activeClientRequestCount > 0 {
                try? await Task<Never, Never>.sleep(nanoseconds: 10_000_000)
            }
            // The previous request may have been openChannel: it had no
            // channel when this contender first arrived, but may have created
            // one before its request count reached zero. Re-check only after
            // the in-flight operation has committed its result.
            if channels.keys.contains(where: { $0.client == activeClientID }) {
                clientTransitionInProgress = false
                return .json(409, [
                    "error": "Reader is busy with another client's active download",
                    "code": "reader_busy"
                ])
            }
            await closeChannelsOwned(by: activeClientID)
            activeClientID = clientID
            clientTransitionInProgress = false
        }

        let response = await operation()
        activeClientRequestCount = max(0, activeClientRequestCount - 1)
        if closing && !channels.keys.contains(where: { $0.client == clientID }) {
            activeClientID = nil
        }
        return response
    }

    @MainActor
    private func closeChannelsOwned(by clientID: String?) async {
        guard let clientID else { return }
        let owned = channels.filter { $0.key.client == clientID }
        let grouped = Dictionary(grouping: owned, by: { $0.key.reader })

        for (readerName, entries) in grouped {
            if let reader = api.reader(named: readerName) {
                try? await withReaderLock(reader) {
                    for channel in Set(entries.map(\.value)) where channel > 0 {
                        _ = try? await transmitWithChaining(
                            Data([0x00, 0x70, 0x80, UInt8(channel), 0x00]),
                            reader: reader
                        )
                    }
                }
            }
        }
        channels.keys.filter { $0.client == clientID }.forEach { channels.removeValue(forKey: $0) }
    }

    @MainActor
    private func withReaderLock<T>(_ reader: BLEReader,
                                   operation: () async throws -> T) async throws -> T {
        while busyReaders.contains(reader.id) {
            try Task.checkCancellation()
            try await Task<Never, Never>.sleep(nanoseconds: 10_000_000)
        }
        busyReaders.insert(reader.id)
        defer { busyReaders.remove(reader.id) }
        return try await operation()
    }

    private func clientID(for connection: NWConnection) -> String {
        if case let .hostPort(host, _) = connection.endpoint { return String(describing: host) }
        return String(describing: connection.endpoint)
    }

    private func mappedCLA(_ cla: UInt8, channel: Int) throws -> UInt8 {
        guard channel >= 0 && channel < 20 else { throw RemobleError.message("Invalid logical channel \(channel)") }
        if channel < 4 { return (cla & 0xFC) | UInt8(channel) }
        return (cla & 0xF0) | 0x40 | UInt8(channel - 4)
    }

    private static func encrypt(_ response: HTTPResponse, password: String) throws -> HTTPResponse {
        let encoded = try crypt(response.body, password: password, operation: CCOperation(kCCEncrypt))
        return .json(response.status, ["data": encoded])
    }

    private static func crypt(_ clearData: Data, password: String, operation: CCOperation) throws -> String {
        let key = Data(SHA256.hash(data: Data(password.utf8)))
        guard operation == CCOperation(kCCEncrypt) else { throw RemoCryptoError.invalidCiphertext }
        var iv = Data(count: kCCBlockSizeAES128)
        let randomStatus = iv.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, kCCBlockSizeAES128, bytes.baseAddress!)
        }
        guard randomStatus == errSecSuccess else { throw RemoCryptoError.randomFailure(randomStatus) }
        let cipher = try aes(clearData, key: key, iv: iv, operation: operation)
        return (iv + cipher).base64EncodedString()
    }

    private static func crypt(_ encryptedBase64: String, password: String, operation: CCOperation) throws -> Data {
        guard operation == CCOperation(kCCDecrypt),
              let combined = Data(base64Encoded: encryptedBase64),
              combined.count > kCCBlockSizeAES128 else { throw RemoCryptoError.invalidCiphertext }
        let key = Data(SHA256.hash(data: Data(password.utf8)))
        let iv = Data(combined.prefix(kCCBlockSizeAES128))
        let cipher = Data(combined.dropFirst(kCCBlockSizeAES128))
        guard cipher.count.isMultiple(of: kCCBlockSizeAES128) else { throw RemoCryptoError.invalidCiphertext }
        return try aes(cipher, key: key, iv: iv, operation: operation)
    }

    private static func aes(_ input: Data, key: Data, iv: Data, operation: CCOperation) throws -> Data {
        var output = Data(count: input.count + kCCBlockSizeAES128)
        let outputCapacity = output.count
        var outputLength = 0
        let status = output.withUnsafeMutableBytes { outputBytes in
            input.withUnsafeBytes { inputBytes in
                key.withUnsafeBytes { keyBytes in
                    iv.withUnsafeBytes { ivBytes in
                        CCCrypt(
                            operation,
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionPKCS7Padding),
                            keyBytes.baseAddress, key.count,
                            ivBytes.baseAddress,
                            inputBytes.baseAddress, input.count,
                            outputBytes.baseAddress, outputCapacity,
                            &outputLength
                        )
                    }
                }
            }
        }
        guard status == kCCSuccess else { throw RemoCryptoError.cryptoFailure(status) }
        output.removeSubrange(outputLength..<output.count)
        return output
    }

    private static func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8), b = Array(rhs.utf8)
        var difference = a.count ^ b.count
        for index in 0..<max(a.count, b.count) {
            let left = index < a.count ? a[index] : 0
            let right = index < b.count ? b[index] : 0
            difference |= Int(left ^ right)
        }
        return difference == 0
    }
}

private struct HTTPRequest {
    let method: String
    let path: String
    let json: Any?
    let clientID: String?
    let headers: [String: String]

    func replacingJSON(_ json: Any?) -> HTTPRequest {
        HTTPRequest(method: method, path: path, json: json, clientID: clientID, headers: headers)
    }

    static func parse(_ data: Data) -> HTTPRequest? {
        let marker = Data("\r\n\r\n".utf8)
        guard let headerRange = data.range(of: marker),
              let headerText = String(data: data[..<headerRange.lowerBound], encoding: .utf8) else { return nil }
        let lines = headerText.components(separatedBy: "\r\n")
        let first = lines.first?.split(separator: " ") ?? []
        guard first.count >= 2 else { return nil }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            let parts = line.split(separator: ":", maxSplits: 1)
            guard parts.count == 2 else { continue }
            let name = parts[0].lowercased()
            // First occurrence wins, avoiding ambiguity and duplicate-key
            // traps for security-sensitive headers such as Authorization.
            if headers[name] == nil {
                headers[name] = parts[1].trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        let contentLength = headers["content-length"].flatMap(Int.init) ?? 0
        let bodyStart = headerRange.upperBound
        guard data.count >= bodyStart + contentLength else { return nil }
        let body = data.subdata(in: bodyStart..<(bodyStart + contentLength))
        let json = body.isEmpty ? nil : try? JSONSerialization.jsonObject(with: body)
        let clientID = headers["x-remoble-client"].flatMap { $0.isEmpty ? nil : $0 }
        return HTTPRequest(method: String(first[0]), path: String(first[1]).components(separatedBy: "?")[0],
                           json: json, clientID: clientID, headers: headers)
    }
}

private enum RemoCryptoError: LocalizedError {
    case invalidEnvelope
    case invalidCiphertext
    case randomFailure(OSStatus)
    case cryptoFailure(CCCryptorStatus)

    var errorDescription: String? {
        switch self {
        case .invalidEnvelope: return "Encrypted JSON body required"
        case .invalidCiphertext: return "Invalid encrypted request"
        case let .randomFailure(status): return "Unable to generate encryption IV (\(status))"
        case let .cryptoFailure(status): return "AES operation failed (\(status))"
        }
    }
}

private struct HTTPResponse {
    let status: Int
    let body: Data

    static func json(_ status: Int, _ object: Any) -> HTTPResponse {
        let body = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
        return HTTPResponse(status: status, body: body)
    }
}

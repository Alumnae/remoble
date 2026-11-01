@preconcurrency import CoreBluetooth
import Foundation

enum ReaderKind: String, Codable {
    case nble, tyBle, beeSim, estkme, estkmeRed, simLink

    var label: String {
        switch self {
        case .nble: return "Secure BLE card"
        case .tyBle: return "TYBLE card"
        case .beeSim, .estkme, .estkmeRed, .simLink: return "BLE smart-card"
        }
    }
}

struct BLEReader: Identifiable, Equatable {
    let id: UUID; let name: String; let kind: ReaderKind; let peripheral: CBPeripheral
    static func == (lhs: BLEReader, rhs: BLEReader) -> Bool { lhs.id == rhs.id }
}

private struct RememberedReader: Codable {
    let id: UUID
    let name: String
    let kind: ReaderKind
    let eid: String?

    init(id: UUID, name: String, kind: ReaderKind, eid: String? = nil) {
        self.id = id
        self.name = name
        self.kind = kind
        self.eid = eid
    }
}

@MainActor
final class RemobleBridge: NSObject, ObservableObject, @preconcurrency CBCentralManagerDelegate {
    @Published private(set) var readers: [BLEReader] = []
    @Published private(set) var connectedReaderIDs = Set<UUID>()
    @Published private(set) var connectingReaderIDs = Set<UUID>()
    @Published private(set) var enabledReaderIDs = Set<UUID>()
    @Published private(set) var selectedReader: BLEReader?
    @Published private(set) var readerRSSI: [UUID: Int] = [:]
    private var readerRSSISamples: [UUID: [Int]] = [:]
    @Published private(set) var scanning = false
    @Published private(set) var connectionStatus = "Disconnected"
    @Published private(set) var lastError: String?
    @Published private(set) var serverRunning = false
    @Published private(set) var serverStatus = "Stopped"
    @Published private(set) var serverURL = "http://device-address:33777"
    @Published private(set) var httpPort: UInt16 = 33777
    @Published private(set) var minimumRSSI = -88
    @Published private(set) var httpPasswordConfigured = false

    private lazy var central = CBCentralManager(delegate: self, queue: .main, options: [
        CBCentralManagerOptionRestoreIdentifierKey: "ee.nekoko.remoble.central",
        CBCentralManagerOptionShowPowerAlertKey: true
    ])
    private var sessions: [UUID: BLEDeviceSession] = [:]
    private var connectContinuations: [UUID: CheckedContinuation<Void, Error>] = [:]
    private var connectAttemptTokens: [UUID: UUID] = [:]
    private var autoReconnectIDs = Set<UUID>()
    /// Suppresses callback-driven reconnects while one task owns the complete
    /// disconnect/new-session/authentication cycle for this reader.
    private var retickInProgressIDs = Set<UUID>()
    /// Readers whose card reset has completed but whose fresh GATT handshake
    /// has not completed yet. HTTP must remain parked during this phase.
    private var recoveringReaderIDs = Set<UUID>()
    /// A rejected PIN must only be cleared by an explicit local action. Remote
    /// discovery/retry traffic must never silently reconnect with the old PIN.
    private var authenticationBlockedReaderIDs = Set<UUID>()
    /// Prevents NekokoLPA2's ten openChannel retries from each launching a new
    /// physical off/on cycle for the same AID failure.
    private var openChannelRecoveryCooldowns: [UUID: Date] = [:]
    private var manuallyDisconnectedIDs = Set<UUID>()
    private var transmittingReaderIDs = Set<UUID>()
    private var rssiPollingIDs = Set<UUID>()
    private let defaults = UserDefaults.standard
    private var httpPasswordValue = ""
    private lazy var server = RemoHTTPServer(api: self)
    private weak var audioController: BackgroundAudioKeeper?
    private static let enabledReadersKey = "http.enabled.reader.ids"
    private static let httpPortKey = "http.listener.port"
    private static let defaultHTTPPort: UInt16 = 33777
    private static let minimumRSSIKey = "ble.minimum.rssi"

    override init() {
        super.init()
        enabledReaderIDs = Set(defaults.stringArray(forKey: Self.enabledReadersKey)?.compactMap(UUID.init(uuidString:)) ?? [])
        let savedPort = defaults.integer(forKey: Self.httpPortKey)
        if (1024...65535).contains(savedPort) {
            httpPort = UInt16(savedPort)
        } else {
            httpPort = Self.defaultHTTPPort
        }
        if defaults.object(forKey: Self.minimumRSSIKey) != nil {
            minimumRSSI = min(-50, max(-120, defaults.integer(forKey: Self.minimumRSSIKey)))
        }
        httpPasswordValue = HTTPPasswordStore.load() ?? ""
        httpPasswordConfigured = !httpPasswordValue.isEmpty
        _ = central
        refreshServerURL()
    }

    var connectedReaders: [BLEReader] { readers.filter { connectedReaderIDs.contains($0.id) } }
    func isConnected(_ reader: BLEReader) -> Bool { connectedReaderIDs.contains(reader.id) }
    func isConnecting(_ reader: BLEReader) -> Bool { connectingReaderIDs.contains(reader.id) }
    func isReaderEnabled(_ reader: BLEReader) -> Bool { enabledReaderIDs.contains(reader.id) }
    func rssi(for reader: BLEReader) -> Int? { readerRSSI[reader.id] }

    func setMinimumRSSI(_ value: Int) {
        minimumRSSI = min(-50, max(-120, value))
        defaults.set(minimumRSSI, forKey: Self.minimumRSSIKey)
    }

    func eid(for reader: BLEReader) -> String? {
        loadRememberedReaders().first(where: { $0.id == reader.id })?.eid
    }

    /// Returns a recent median RSSI before a new card transaction. A missing
    /// value is not treated as unsafe because restored peripherals can connect
    /// before producing their first RSSI callback.
    func preflightRSSI(for reader: BLEReader) async -> Int? {
        guard reader.peripheral.state == .connected else { return readerRSSI[reader.id] }
        for _ in 0..<3 {
            reader.peripheral.readRSSI()
            try? await Task<Never, Never>.sleep(nanoseconds: 250_000_000)
        }
        let samples = Array(readerRSSISamples[reader.id, default: []].suffix(5)).sorted()
        return samples.isEmpty ? readerRSSI[reader.id] : samples[samples.count / 2]
    }
    func select(_ reader: BLEReader) { selectedReader = reader }

    func setReaderEnabled(_ reader: BLEReader, enabled: Bool) {
        selectedReader = reader
        if enabled {
            enabledReaderIDs.insert(reader.id)
            remember(reader)
            manuallyDisconnectedIDs.remove(reader.id)
            authenticationBlockedReaderIDs.remove(reader.id)
            openChannelRecoveryCooldowns.removeValue(forKey: reader.id)
        } else {
            enabledReaderIDs.remove(reader.id)
            openChannelRecoveryCooldowns.removeValue(forKey: reader.id)
            disconnect(reader)
        }
        defaults.set(enabledReaderIDs.map(\.uuidString).sorted(), forKey: Self.enabledReadersKey)
    }

    func restoreAndScan() { restoreRememberedReaders(); startScan() }

    private func restoreRememberedReaders() {
        var saved = loadRememberedReaders()
        if saved.isEmpty,
           let raw = defaults.string(forKey: "remembered.reader.id"), let id = UUID(uuidString: raw),
           let name = defaults.string(forKey: "remembered.reader.name"),
           let kindRaw = defaults.string(forKey: "remembered.reader.kind"), let kind = ReaderKind(rawValue: kindRaw) {
            saved = [RememberedReader(id: id, name: name, kind: kind)]; saveRememberedReaders(saved)
        }
        // Preserve behavior for upgrades from the original single-reader build.
        if defaults.object(forKey: Self.enabledReadersKey) == nil {
            enabledReaderIDs = Set(saved.map(\.id))
            defaults.set(enabledReaderIDs.map(\.uuidString).sorted(), forKey: Self.enabledReadersKey)
        }
        let peripherals = central.retrievePeripherals(withIdentifiers: saved.map(\.id))
        for item in saved {
            guard let peripheral = peripherals.first(where: { $0.identifier == item.id }) else { continue }
            accept(peripheral: peripheral, name: item.name, kind: item.kind)
        }
        reconnectRememberedReaders()
    }

    func startScan() {
        guard central.state == .poweredOn else {
            connectionStatus = central.state == .unauthorized ? "Bluetooth permission denied" : "Waiting for Bluetooth"; _ = central; return
        }
        scanning = true
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        Task {
            try? await Task<Never, Never>.sleep(nanoseconds: 15_000_000_000)
            guard scanning else { return }; central.stopScan(); scanning = false
        }
    }

    private func pinKey(for reader: BLEReader) -> String { "nble.pin.\(reader.id.uuidString)" }

    func savedPin(for reader: BLEReader) -> String { defaults.string(forKey: pinKey(for: reader)) ?? "" }

    private func clearSavedPin(for reader: BLEReader) {
        defaults.removeObject(forKey: pinKey(for: reader))
    }

    /// Never retry a rejected cached credential in the background. Remove only
    /// this reader's PIN and wait for an explicit local correction.
    private func handleAuthenticationFailure(_ error: Error, for reader: BLEReader) {
        clearSavedPin(for: reader)
        authenticationBlockedReaderIDs.insert(reader.id)
        manuallyDisconnectedIDs.insert(reader.id)
        recoveringReaderIDs.remove(reader.id)
        connectedReaderIDs.remove(reader.id)
        connectionStatus = "PIN required for \(reader.name)"
        lastError = "\(error.localizedDescription). Saved PIN cleared; enter the current 6-digit hexadecimal PIN."
    }

    func connect(_ reader: BLEReader, pin: String) async {
        selectedReader = reader; lastError = nil
        do { try await connectThrowing(reader, pin: pin) }
        catch {
            if !enabledReaderIDs.contains(reader.id) || manuallyDisconnectedIDs.contains(reader.id) {
                connectionStatus = connectedReaderIDs.isEmpty ? "Disconnected" : "\(connectedReaderIDs.count) BLE card connected"
                return
            }
            if isAuthenticationError(error) {
                handleAuthenticationFailure(error, for: reader)
                return
            }
            connectionStatus = "Connection failed: \(reader.name)"
            lastError = error.localizedDescription
        }
    }

    /// Applies a new per-reader PIN by rebuilding the GATT session. A regular
    /// `connect` intentionally reuses a healthy session, which is not enough
    /// when the user explicitly asks to re-authenticate with another PIN.
    func reconnect(_ reader: BLEReader, pin: String) async {
        selectedReader = reader
        lastError = nil
        setReaderEnabled(reader, enabled: true)
        do {
            try await performAutomaticRetick(reader, pin: pin)
        } catch {
            // Do not conceal a rejected new PIN by immediately reconnecting
            // with the old saved value in the background.
            if isAuthenticationError(error) {
                handleAuthenticationFailure(error, for: reader)
                return
            }
            recoveringReaderIDs.remove(reader.id)
            manuallyDisconnectedIDs.insert(reader.id)
            connectionStatus = "Connection failed: \(reader.name)"
            lastError = error.localizedDescription
        }
    }

    private func connectThrowing(_ reader: BLEReader, pin: String) async throws {
        try ensureConnectionAllowed(for: reader)
        if sessions[reader.id]?.isConnected == true {
            connectedReaderIDs.insert(reader.id)
            recoveringReaderIDs.remove(reader.id)
            connectionStatus = "\(connectedReaderIDs.count) BLE card connected"
            lastError = nil
            authenticationBlockedReaderIDs.remove(reader.id)
            startRSSIPolling(reader)
            return
        }
        while connectingReaderIDs.contains(reader.id) {
            try Task.checkCancellation()
            try ensureConnectionAllowed(for: reader)
            try await Task<Never, Never>.sleep(nanoseconds: 50_000_000)
        }
        if sessions[reader.id]?.isConnected == true {
            connectedReaderIDs.insert(reader.id)
            recoveringReaderIDs.remove(reader.id)
            connectionStatus = "\(connectedReaderIDs.count) BLE card connected"
            lastError = nil
            authenticationBlockedReaderIDs.remove(reader.id)
            startRSSIPolling(reader)
            return
        }
        if sessions[reader.id]?.isSettling == true { throw RemobleError.message("\(reader.name) is applying a profile switch; retry shortly") }
        connectingReaderIDs.insert(reader.id); connectionStatus = "Connecting to \(reader.name)…"
        defer { connectingReaderIDs.remove(reader.id) }
        let session = sessions[reader.id] ?? makeSession(for: reader); sessions[reader.id] = session
        var attempt = 0
        while true {
            try ensureConnectionAllowed(for: reader)
            attempt += 1
            do {
                try await establish(session); try await session.prepare(pin: pin)
                connectedReaderIDs.insert(reader.id)
                recoveringReaderIDs.remove(reader.id)
                connectionStatus = "\(connectedReaderIDs.count) BLE card connected"
                lastError = nil
                authenticationBlockedReaderIDs.remove(reader.id)
                remember(reader)
                if reader.kind == .nble {
                    defaults.set(pin.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
                                 forKey: pinKey(for: reader))
                }
                startRSSIPolling(reader)
                return
            } catch BLEProfileError.reconnectRequired where attempt < 3 {
                await resetTransport(session)
                try ensureConnectionAllowed(for: reader)
                try await Task<Never, Never>.sleep(nanoseconds: 4_000_000_000)
                try ensureConnectionAllowed(for: reader)
            } catch {
                if isAuthenticationError(error) { clearSavedPin(for: reader) }
                if attempt < 3, isRetryableConnectionError(error) {
                    await resetTransport(session, error: error)
                    try ensureConnectionAllowed(for: reader)
                    try await Task<Never, Never>.sleep(nanoseconds: UInt64(attempt) * 1_000_000_000)
                    try ensureConnectionAllowed(for: reader)
                    continue
                }
                await resetTransport(session, error: error)
                connectedReaderIDs.remove(reader.id)
                throw error
            }
        }
    }

    private func ensureConnectionAllowed(for reader: BLEReader) throws {
        guard enabledReaderIDs.contains(reader.id) else {
            throw RemobleError.message("BLE reader disabled")
        }
        guard !manuallyDisconnectedIDs.contains(reader.id) else {
            throw RemobleError.message("BLE connection cancelled")
        }
    }

    private func resetTransport(_ session: BLEDeviceSession, error: Error? = nil) async {
        let peripheral = session.reader.peripheral
        session.invalidate(error: error)
        central.cancelPeripheralConnection(peripheral)
        for _ in 0..<100 where peripheral.state != .disconnected {
            try? await Task<Never, Never>.sleep(nanoseconds: 50_000_000)
        }
    }

    private func isRetryableConnectionError(_ error: Error) -> Bool {
        let text = error.localizedDescription.lowercased()
        return !isAuthenticationError(error)
            && !text.contains("cancelled")
            && !text.contains("disabled")
    }

    private func isAuthenticationError(_ error: Error) -> Bool {
        let text = error.localizedDescription.lowercased()
        return text.contains("wrong 9esim")
            || text.contains("wrong ble pin")
            || text.contains("hexadecimal pin")
            || text.contains("authentication rejected")
    }

    private func establish(_ session: BLEDeviceSession) async throws {
        let peripheral = session.reader.peripheral
        if peripheral.state != .connected {
            let token = UUID()
            try await withCheckedThrowingContinuation { continuation in
                connectAttemptTokens[peripheral.identifier] = token
                connectContinuations[peripheral.identifier] = continuation
                central.connect(peripheral, options: [
                    CBConnectPeripheralOptionNotifyOnConnectionKey: true,
                    CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
                    CBConnectPeripheralOptionNotifyOnNotificationKey: true
                ])
                Task { @MainActor [weak self] in
                    try? await Task<Never, Never>.sleep(nanoseconds: 12_000_000_000)
                    guard let self,
                          self.connectAttemptTokens[peripheral.identifier] == token else { return }
                    self.connectAttemptTokens.removeValue(forKey: peripheral.identifier)
                    self.central.cancelPeripheralConnection(peripheral)
                    self.connectContinuations.removeValue(forKey: peripheral.identifier)?.resume(
                        throwing: RemobleError.message("BLE connection timed out"))
                }
            }
        }
        try await session.discoverTransport()
    }

    func disconnect(_ reader: BLEReader, forget: Bool = false) {
        manuallyDisconnectedIDs.insert(reader.id)
        server.invalidateChannels(for: reader)
        connectAttemptTokens.removeValue(forKey: reader.id)
        connectContinuations.removeValue(forKey: reader.id)?.resume(
            throwing: RemobleError.message("BLE connection cancelled"))
        sessions[reader.id]?.invalidate(); central.cancelPeripheralConnection(reader.peripheral); sessions.removeValue(forKey: reader.id)
        connectedReaderIDs.remove(reader.id); connectingReaderIDs.remove(reader.id)
        recoveringReaderIDs.remove(reader.id)
        if forget {
            var saved = loadRememberedReaders(); saved.removeAll { $0.id == reader.id }; saveRememberedReaders(saved)
            clearSavedPin(for: reader)
        }
        connectionStatus = connectedReaderIDs.isEmpty ? "Disconnected" : "\(connectedReaderIDs.count) BLE card connected"
    }

    /// Removes all persisted and live state belonging to one physical reader.
    /// A later explicit scan may discover it again if it is still advertising.
    func forget(_ reader: BLEReader) {
        disconnect(reader, forget: true)
        enabledReaderIDs.remove(reader.id)
        authenticationBlockedReaderIDs.remove(reader.id)
        openChannelRecoveryCooldowns.removeValue(forKey: reader.id)
        autoReconnectIDs.remove(reader.id)
        retickInProgressIDs.remove(reader.id)
        transmittingReaderIDs.remove(reader.id)
        rssiPollingIDs.remove(reader.id)
        readerRSSI.removeValue(forKey: reader.id)
        readerRSSISamples.removeValue(forKey: reader.id)
        readers.removeAll { $0.id == reader.id }
        if selectedReader?.id == reader.id { selectedReader = nil }
        defaults.set(enabledReaderIDs.map(\.uuidString).sorted(), forKey: Self.enabledReadersKey)
        connectionStatus = connectedReaderIDs.isEmpty
            ? "Reader forgotten" : "\(connectedReaderIDs.count) BLE card connected"
        lastError = nil
    }

    func disconnect() { if let selectedReader { disconnect(selectedReader) } }

    var availableReadersForHTTP: [BLEReader] {
        readers.filter { enabledReaderIDs.contains($0.id) }
    }

    func reader(named remoteName: String?) -> BLEReader? {
        guard let remoteName, !remoteName.isEmpty else { return availableReadersForHTTP.first }
        if let exact = availableReadersForHTTP.first(where: { readerName($0) == remoteName }) { return exact }
        // Accept the old name-bearing format during upgrades. Identity is the
        // CoreBluetooth UUID after the final `|`, never the advertised name.
        guard let rawID = remoteName.split(separator: "|").last,
              let id = UUID(uuidString: String(rawID)) else { return nil }
        return availableReadersForHTTP.first { $0.id == id }
    }

    func isSettling(_ reader: BLEReader) -> Bool {
        sessions[reader.id]?.isSettling == true || recoveringReaderIDs.contains(reader.id)
    }

    func isReady(_ reader: BLEReader) -> Bool {
        connectedReaderIDs.contains(reader.id)
            && sessions[reader.id]?.isConnected == true
            && !isSettling(reader)
    }

    func isAuthenticationBlocked(_ reader: BLEReader) -> Bool {
        authenticationBlockedReaderIDs.contains(reader.id)
    }

    /// Wakes only the reader named by the remote command. Discovery requests
    /// must not start every remembered NBLE handshake at the same time.
    func wakeReaderForRemoteAccess(_ reader: BLEReader) {
        guard enabledReaderIDs.contains(reader.id),
              !authenticationBlockedReaderIDs.contains(reader.id) else { return }
        manuallyDisconnectedIDs.remove(reader.id)
        scheduleAutoReconnect(reader.id)
    }

    /// Keeps one HTTP attempt below RemoCardAdapter's fixed 15-second timeout.
    /// The BLE connect/recovery remains alive in its single background task;
    /// later attempts observe the same task instead of queuing another reset.
    func waitUntilReadyForHTTP(_ reader: BLEReader, timeout: TimeInterval = 11) async -> Bool {
        wakeReaderForRemoteAccess(reader)
        let deadline = Date().addingTimeInterval(timeout)
        while !isReady(reader), Date() < deadline {
            if authenticationBlockedReaderIDs.contains(reader.id)
                || !enabledReaderIDs.contains(reader.id) { return false }
            try? await Task<Never, Never>.sleep(nanoseconds: 200_000_000)
        }
        return isReady(reader)
    }

    /// Keeps a legacy RemoCard request pending while an NBLE writer is in its
    /// mandatory quiet reset window. NekokoLPA2's RemoCard transport has no
    /// proactive-refresh hook and gives up after ten quick openChannel retries;
    /// spacing those retries here lets the host reset finish without sending
    /// any BLE traffic. Keep this below RemoCardAdapter's 15 second HTTP timeout.
    func waitUntilSettledForHTTP(_ reader: BLEReader, timeout: TimeInterval = 12) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while isSettling(reader), Date() < deadline {
            try? await Task<Never, Never>.sleep(nanoseconds: 200_000_000)
        }
        return !isSettling(reader)
    }

    func startServer() {
        guard !serverRunning else { return }
        do {
            try server.start(port: httpPort)
            serverRunning = true
            serverStatus = "Listening on port \(httpPort)"
            refreshServerURL()
        } catch { serverStatus = "Failed: \(error.localizedDescription)"; lastError = error.localizedDescription }
    }
    func stopServer() { server.stop(); serverRunning = false; serverStatus = "Stopped" }

    func attachAudio(_ audio: BackgroundAudioKeeper) {
        audioController = audio
    }

    func remoteAudioState() -> [String: Any] {
        guard let audioController else { return ["available": false] }
        var state: [String: Any] = [
            "available": true,
            "playing": audioController.isPlaying,
            "sound": audioController.selectedSound.rawValue,
            "volume": audioController.volume,
            "status": audioController.status,
            "updatedAt": Int(audioController.updatedAt.timeIntervalSince1970 * 1_000)
        ]
        state["timer"] = audioController.sleepMinutes ?? NSNull()
        return state
    }

    func remotePlay(sound: String?) throws {
        guard let audioController else { throw RemobleError.message("Audio player unavailable") }
        if let sound {
            guard let selected = NoiseSound(rawValue: sound) else {
                throw RemobleError.message("Unknown noise sound")
            }
            audioController.select(selected)
        }
        audioController.play()
    }

    func remoteStop() throws {
        guard let audioController else { throw RemobleError.message("Audio player unavailable") }
        audioController.pause()
    }

    func remoteSetVolume(_ value: Double) throws {
        guard let audioController else { throw RemobleError.message("Audio player unavailable") }
        guard (0...1).contains(value) else { throw RemobleError.message("Volume must be between 0 and 1") }
        audioController.setVolume(value)
    }

    func remoteSetTimer(_ minutes: Int?) throws {
        guard let audioController else { throw RemobleError.message("Audio player unavailable") }
        if let minutes, !(1...1_440).contains(minutes) {
            throw RemobleError.message("Timer must be between 1 and 1440 minutes")
        }
        audioController.setSleepTimer(minutes: minutes)
    }

    /// Persists the listener port and immediately moves a running HTTP bridge
    /// to the new endpoint. Active HTTP clients must reconnect to the new URL;
    /// the BLE sessions themselves are left untouched.
    func setHTTPPort(_ value: String) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parsed = Int(trimmed), (1024...65535).contains(parsed) else {
            lastError = "HTTP port must be a number between 1024 and 65535"
            return
        }
        let newPort = UInt16(parsed)
        guard newPort != httpPort else {
            refreshServerURL()
            lastError = nil
            return
        }

        let shouldRestart = serverRunning
        if shouldRestart { stopServer() }
        httpPort = newPort
        defaults.set(parsed, forKey: Self.httpPortKey)
        refreshServerURL()
        lastError = nil
        if shouldRestart { startServer() }
    }

    private func refreshServerURL() {
        serverURL = "http://\(Self.localIPv4Address() ?? "device-address"):\(httpPort)"
    }

    var httpPassword: String { httpPasswordValue }

    func setHTTPPassword(_ password: String) {
        let value = password.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try HTTPPasswordStore.save(value)
            httpPasswordValue = value
            httpPasswordConfigured = !value.isEmpty
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { restoreRememberedReaders(); startScan() }
        else if central.state != .unknown && central.state != .resetting { connectionStatus = "Bluetooth unavailable" }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let saved = Dictionary(uniqueKeysWithValues: loadRememberedReaders().map { ($0.id, $0) })
        for peripheral in dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? [] {
            let item = saved[peripheral.identifier]; let name = peripheral.name ?? item?.name ?? "BLE card"
            guard let kind = item?.kind ?? Self.detect(name: name, services: []) else { continue }
            accept(peripheral: peripheral, name: name, kind: kind)
        }
        reconnectRememberedReaders()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let platformName = peripheral.name
        // CoreBluetooth can retain an old six-character 9eSIM name after the
        // card starts advertising its eight-digit TYBLE UID. Authentication
        // must use the current advertised UID, so it takes precedence here.
        let name = [advertisedName, platformName]
            .compactMap { $0 }
            .first(where: TYBLEProtocol.isDeviceName)
            ?? platformName ?? advertisedName ?? "BLE card"
        let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        guard let kind = Self.detect(name: name, services: services) else { return }
        updateRSSI(RSSI.intValue, for: peripheral.identifier)
        accept(peripheral: peripheral, name: name, kind: kind)
        if !connectedReaderIDs.contains(peripheral.identifier),
           !connectingReaderIDs.contains(peripheral.identifier),
           loadRememberedReaders().contains(where: { $0.id == peripheral.identifier }) {
            reconnectRememberedReaders()
        }
    }

    private func accept(peripheral: CBPeripheral, name: String, kind: ReaderKind) {
        let reader = BLEReader(id: peripheral.identifier, name: name, kind: kind, peripheral: peripheral)
        if let i = readers.firstIndex(where: { $0.id == reader.id }) {
            let current = readers[i]
            if current.name != name || current.kind != kind || current.peripheral !== peripheral {
                if current.kind != kind || (kind == .tyBle && current.name != name) {
                    // Replace a restored legacy-NBLE session when the live
                    // advertisement proves that this is an eight-digit B1.
                    sessions[reader.id]?.invalidate()
                    sessions.removeValue(forKey: reader.id)
                    connectedReaderIDs.remove(reader.id)
                    central.cancelPeripheralConnection(current.peripheral)
                }
                readers[i] = reader
            }
        }
        else { readers.append(reader); readers.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending } }
    }

    private func makeSession(for reader: BLEReader) -> BLEDeviceSession {
        BLEDeviceSession(reader: reader) { [weak self] value in
            self?.updateRSSI(value, for: reader.id)
        }
    }

    private func updateRSSI(_ value: Int, for id: UUID) {
        // CoreBluetooth uses +127 when RSSI is unavailable.
        guard value < 0, value > -127 else { return }
        let smoothed = readerRSSI[id].map { Int((Double($0) * 0.7 + Double(value) * 0.3).rounded()) } ?? value
        if readerRSSI[id] != smoothed { readerRSSI[id] = smoothed }
        var samples = readerRSSISamples[id, default: []]
        samples.append(value)
        if samples.count > 8 { samples.removeFirst(samples.count - 8) }
        readerRSSISamples[id] = samples
    }

    private func startRSSIPolling(_ reader: BLEReader) {
        guard !rssiPollingIDs.contains(reader.id) else { return }
        rssiPollingIDs.insert(reader.id)
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.rssiPollingIDs.remove(reader.id) }
            while reader.peripheral.state == .connected && self.enabledReaderIDs.contains(reader.id) {
                if !self.transmittingReaderIDs.contains(reader.id) { reader.peripheral.readRSSI() }
                try? await Task<Never, Never>.sleep(nanoseconds: 5_000_000_000)
            }
        }
    }

    private static func detect(name: String, services: [CBUUID]) -> ReaderKind? {
        let lower = name.lowercased()
        if TYBLEProtocol.isDeviceName(name)
            || services.contains(CBUUID(string: "49535343-FE7D-4AE5-8FA9-9FAFD205E455")) {
            return .tyBle
        }
        if lower.contains("beesim") || services.contains(CBUUID(string: "AE30")) { return .beeSim }
        if lower.range(of: "estkme[-_ ]*red", options: .regularExpression) != nil { return .estkmeRed }
        if lower.contains("estkme") { return .estkme }
        if lower.contains("esim_writer") { return .simLink }
        if lower.range(of: "^9esim[0-9a-f]{6}$", options: .regularExpression) != nil || lower.contains("glocal") { return .nble }
        return nil
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectAttemptTokens.removeValue(forKey: peripheral.identifier)
        connectContinuations.removeValue(forKey: peripheral.identifier)?.resume()
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectAttemptTokens.removeValue(forKey: peripheral.identifier)
        connectContinuations.removeValue(forKey: peripheral.identifier)?.resume(throwing: error ?? RemobleError.message("Unable to connect to BLE reader"))
    }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) { handleDisconnect(peripheral, error: error) }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) { handleDisconnect(peripheral, error: error) }

    private func handleDisconnect(_ peripheral: CBPeripheral, error: Error?) {
        connectAttemptTokens.removeValue(forKey: peripheral.identifier)
        connectContinuations.removeValue(forKey: peripheral.identifier)?.resume(throwing: error ?? RemobleError.message("BLE reader disconnected"))
        sessions[peripheral.identifier]?.invalidate(error: error); connectedReaderIDs.remove(peripheral.identifier)
        if let reader = readers.first(where: { $0.id == peripheral.identifier }) {
            server.invalidateChannels(for: reader)
        }
        if let error { lastError = error.localizedDescription }
        connectionStatus = connectedReaderIDs.isEmpty ? "Disconnected; waiting to reconnect" : "\(connectedReaderIDs.count) BLE card connected"
        if !retickInProgressIDs.contains(peripheral.identifier)
            && !connectingReaderIDs.contains(peripheral.identifier) {
            scheduleAutoReconnect(peripheral.identifier)
        }
    }

    private func scheduleAutoReconnect(_ id: UUID, initialDelay: UInt64 = 0) {
        guard enabledReaderIDs.contains(id), loadRememberedReaders().contains(where: { $0.id == id }),
              !manuallyDisconnectedIDs.contains(id),
              !authenticationBlockedReaderIDs.contains(id),
              !retickInProgressIDs.contains(id),
              !autoReconnectIDs.contains(id) else { return }
        autoReconnectIDs.insert(id)
        Task {
            defer { autoReconnectIDs.remove(id) }; var delay: UInt64 = 1_000_000_000
            if initialDelay > 0 {
                try? await Task<Never, Never>.sleep(nanoseconds: initialDelay)
            }
            while central.state == .poweredOn && !connectedReaderIDs.contains(id) {
                if Task.isCancelled || manuallyDisconnectedIDs.contains(id) || !enabledReaderIDs.contains(id) { return }
                if retickInProgressIDs.contains(id) {
                    try? await Task<Never, Never>.sleep(nanoseconds: 200_000_000)
                    continue
                }
                if let reader = readers.first(where: { $0.id == id }) {
                    if sessions[id]?.isSettling == true {
                        await probeSettlingReader(reader)
                        continue
                    }
                    do { try await connectThrowing(reader, pin: savedPin(for: reader)); return }
                    catch {
                        if Task.isCancelled || manuallyDisconnectedIDs.contains(id) || !enabledReaderIDs.contains(id) { return }
                        if isAuthenticationError(error) {
                            handleAuthenticationFailure(error, for: reader)
                            return
                        }
                        lastError = "\(reader.name): \(error.localizedDescription)"
                        let openChannelRetickSuppressed = openChannelRecoveryCooldowns[id]
                            .map { $0 > Date() } ?? false
                        if !openChannelRetickSuppressed {
                            do {
                                try await performAutomaticRetick(reader)
                                return
                            } catch {
                                if Task.isCancelled || manuallyDisconnectedIDs.contains(id) || !enabledReaderIDs.contains(id) { return }
                                if isAuthenticationError(error) {
                                    handleAuthenticationFailure(error, for: reader)
                                    return
                                }
                                lastError = "\(reader.name) retick failed: \(error.localizedDescription)"
                            }
                        }
                    }
                }
                try? await Task<Never, Never>.sleep(nanoseconds: delay); delay = min(delay * 2, 30_000_000_000)
            }
        }
    }
    private func reconnectRememberedReaders() {
        for (index, item) in loadRememberedReaders().enumerated() where enabledReaderIDs.contains(item.id) {
            scheduleAutoReconnect(item.id, initialDelay: UInt64(index) * 3_000_000_000)
        }
    }

    private func probeSettlingReader(_ reader: BLEReader) async {
        guard reader.kind == .nble, let session = sessions[reader.id] else { return }
        let delay = session.settleProbeDelay
        if delay > 0 {
            try? await Task<Never, Never>.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        }
        guard session.isSettling else { return }
        var ready = false
        var probeError: Error?
        do {
            try await establish(session)
            // Authenticate, but never force writer mode while the card is in a
            // profile transition. Then send only the minimal CAT STATUS probe.
            try await session.prepare(pin: savedPin(for: reader), allowModeSwitch: false)
            if let profile = session.profile {
                let response = try await transmitControl(
                    Data([0x80, 0xF2, 0x00, 0x00, 0x00]),
                    profile: profile)
                ready = response.count >= 2
                    && response[response.count - 2] == 0x90
                    && response[response.count - 1] == 0x00
            }
        } catch {
            probeError = error
            lastError = "\(reader.name) is still resetting: \(error.localizedDescription)"
        }

        if let probeError, isAuthenticationError(probeError) {
            session.endSettling()
            session.invalidate(error: probeError)
            central.cancelPeripheralConnection(reader.peripheral)
            handleAuthenticationFailure(probeError, for: reader)
            return
        }
        let finished = session.recordSettleProbe(ready: ready)

        if ready {
            // The STATUS probe already performed a complete authenticated
            // handshake and proved SW=9000. Keep that exact session, as the
            // Android/Nekoko implementation does; discarding it here caused a
            // second retick and the endless spinner seen by the remote client.
            connectedReaderIDs.insert(reader.id)
            recoveringReaderIDs.remove(reader.id)
            authenticationBlockedReaderIDs.remove(reader.id)
            connectionStatus = "\(connectedReaderIDs.count) BLE card connected"
            lastError = nil
            return
        }

        session.invalidate()
        central.cancelPeripheralConnection(reader.peripheral)
        if finished {
            connectionStatus = "\(reader.name) reset wait expired"
        } else {
            connectionStatus = "\(reader.name) still resetting"
        }
        for _ in 0..<100 where reader.peripheral.state != .disconnected {
            try? await Task<Never, Never>.sleep(nanoseconds: 50_000_000)
        }
        if finished {
            // The deadline expired without a healthy probe. Make one final,
            // single-flight fresh connection, then leave recovery rather than
            // keeping the UI spinner set forever on terminal failure.
            recoveringReaderIDs.insert(reader.id)
            connectionStatus = "Automatically reconnecting \(reader.name)…"
            do {
                try await performAutomaticRetick(reader)
            } catch {
                recoveringReaderIDs.remove(reader.id)
                lastError = "\(reader.name) retick failed: \(error.localizedDescription)"
            }
        }
    }

    /// Reassembles the ISO 7816 status probe exactly like NekokoLPA2. 9eSIM
    /// commonly answers `80 F2 00 00 00` with `6C39`; treating that response
    /// as "still resetting" made every later probe fail forever.
    private func transmitControl(_ command: Data, profile: BLECardProfile) async throws -> Data {
        var current = command
        var output = Data()
        for _ in 0..<8 {
            let response = try await profile.transmit(current)
            guard response.count >= 2 else {
                throw RemobleError.message("Truncated STATUS response")
            }
            let sw1 = response[response.count - 2]
            let sw2 = response[response.count - 1]
            if sw1 == 0x61 {
                output += response.dropLast(2)
                current = Data([current[0], 0xC0, 0x00, 0x00, sw2])
                continue
            }
            if sw1 == 0x6C {
                guard !current.isEmpty else {
                    throw RemobleError.message("Invalid STATUS correction")
                }
                current[current.count - 1] = sw2
                continue
            }
            output += response
            return output
        }
        throw RemobleError.message("STATUS response did not finish reassembling")
    }

    /// Performs the effective part of a manual off/on checkbox cycle while
    /// keeping the slot visible to the remote client: abort every pending
    /// CoreBluetooth operation, discard the GATT session, wait for a physical
    /// disconnect, then build and authenticate an entirely new session.
    private func performAutomaticRetick(_ reader: BLEReader, pin: String? = nil) async throws {
        guard enabledReaderIDs.contains(reader.id) else {
            throw RemobleError.message("BLE reader was disabled")
        }
        guard !retickInProgressIDs.contains(reader.id) else {
            throw RemobleError.message("BLE reader reconnect already in progress")
        }
        retickInProgressIDs.insert(reader.id)
        var reconnected = false
        defer {
            retickInProgressIDs.remove(reader.id)
            if !reconnected { recoveringReaderIDs.remove(reader.id) }
        }
        recoveringReaderIDs.insert(reader.id)
        selectedReader = reader
        enabledReaderIDs.insert(reader.id)
        defaults.set(enabledReaderIDs.map(\.uuidString).sorted(), forKey: Self.enabledReadersKey)
        remember(reader)
        manuallyDisconnectedIDs.remove(reader.id)
        server.invalidateChannels(for: reader)
        connectAttemptTokens.removeValue(forKey: reader.id)
        connectContinuations.removeValue(forKey: reader.id)?.resume(
            throwing: RemobleError.message("BLE connection restarted by automatic retick"))
        sessions[reader.id]?.invalidate()
        sessions.removeValue(forKey: reader.id)
        connectedReaderIDs.remove(reader.id)
        connectingReaderIDs.remove(reader.id)
        central.cancelPeripheralConnection(reader.peripheral)
        for _ in 0..<100 where reader.peripheral.state != .disconnected {
            try? await Task<Never, Never>.sleep(nanoseconds: 50_000_000)
        }
        guard reader.peripheral.state == .disconnected else {
            throw RemobleError.message("BLE disconnect timed out during automatic retick")
        }
        // Match the radio breathing room of the successful manual toggle.
        try await Task<Never, Never>.sleep(nanoseconds: 750_000_000)
        try await connectThrowing(reader, pin: pin ?? savedPin(for: reader))
        reconnected = true
    }

    func beginRecovery(for reader: BLEReader) {
        let session = sessions[reader.id] ?? makeSession(for: reader)
        sessions[reader.id] = session
        session.beginSettling(seconds: 20)
        recoveringReaderIDs.remove(reader.id)
        connectedReaderIDs.remove(reader.id)
        server.invalidateChannels(for: reader)
        central.cancelPeripheralConnection(reader.peripheral)
        connectionStatus = "\(reader.name) requires a quiet reset window"
        scheduleAutoReconnect(reader.id)
    }

    /// Starts at most one physical retick for a run of rejected AIDs. Nekoko's
    /// retry loop may call /openChannel ten times; those calls must observe one
    /// recovery rather than creating ten consecutive disconnects.
    @discardableResult
    func requestOpenChannelRecovery(for reader: BLEReader) -> Bool {
        let now = Date()
        guard enabledReaderIDs.contains(reader.id),
              !authenticationBlockedReaderIDs.contains(reader.id),
              !isSettling(reader),
              !retickInProgressIDs.contains(reader.id),
              openChannelRecoveryCooldowns[reader.id].map({ $0 <= now }) ?? true else {
            return false
        }

        openChannelRecoveryCooldowns[reader.id] = now.addingTimeInterval(180)
        recoveringReaderIDs.insert(reader.id)
        connectionStatus = "Recovering \(reader.name)…"
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.performAutomaticRetick(reader)
            } catch {
                self.recoveringReaderIDs.remove(reader.id)
                if self.isAuthenticationError(error) {
                    self.handleAuthenticationFailure(error, for: reader)
                } else {
                    self.connectionStatus = "Recovery failed: \(reader.name)"
                    self.lastError = error.localizedDescription
                }
            }
        }
        return true
    }

    func markOpenChannelSucceeded(for reader: BLEReader) {
        openChannelRecoveryCooldowns.removeValue(forKey: reader.id)
        recoveringReaderIDs.remove(reader.id)
    }

    /// Foreground activation restores remembered readers without overriding a
    /// reader that is waiting for an explicit local PIN correction.
    func wakeReadersForRemoteAccess() {
        for (index, item) in loadRememberedReaders().filter({ enabledReaderIDs.contains($0.id) }).enumerated() {
            guard !authenticationBlockedReaderIDs.contains(item.id) else { continue }
            manuallyDisconnectedIDs.remove(item.id)
            scheduleAutoReconnect(item.id, initialDelay: UInt64(index) * 3_000_000_000)
        }
    }

    func transmit(_ apdu: Data, reader: BLEReader) async throws -> Data {
        while transmittingReaderIDs.contains(reader.id) {
            try Task.checkCancellation()
            try await Task<Never, Never>.sleep(nanoseconds: 10_000_000)
        }
        transmittingReaderIDs.insert(reader.id)
        defer { transmittingReaderIDs.remove(reader.id) }
        var session = sessions[reader.id]
        if session?.isSettling == true { throw RemobleError.message("\(reader.name) is applying a profile switch; retry shortly") }
        if session?.isConnected != true { try await connectThrowing(reader, pin: savedPin(for: reader)); session = sessions[reader.id] }
        guard let session, let profile = session.profile else { throw RemobleError.message("No BLE reader connected") }
        let refresh = BLEProtocolCodec.requestsProactiveRefresh(apdu); let response = try await profile.transmit(apdu)
        let state = BLEProtocolCodec.cardState(response)
        if reader.kind == .nble && (state == .proactiveRefresh || state == .settling || (refresh && state == .ready)) {
            session.beginSettling(seconds: 20); connectedReaderIDs.remove(reader.id); central.cancelPeripheralConnection(reader.peripheral)
            connectionStatus = "\(reader.name) settling after profile switch"
            // Do not depend on didDisconnectPeripheral to start recovery. iOS
            // can defer that callback while suspended/backgrounded, leaving the
            // reader permanently unchecked and every remote openChannel retry
            // returning false. The task itself waits out the quiet window.
            scheduleAutoReconnect(reader.id)
        } else if reader.kind == .beeSim && refresh && [.ready, .proactiveRefresh].contains(state) {
            try? await profile.profileSwitchCommitted(); session.invalidate(); connectedReaderIDs.remove(reader.id)
            central.cancelPeripheralConnection(reader.peripheral); connectionStatus = "\(reader.name) restarting after profile switch"
        }
        return response
    }

    /// Mirrors an explicit UI checkbox reset. This is intentionally stronger
    /// than a transport-only reconnect: it clears reader channels and session
    /// state, briefly removes the reader from the HTTP whitelist, then restores
    /// the saved selection and authentication before reconnecting.
    func cycleReaderEnabled(_ reader: BLEReader) async throws {
        setReaderEnabled(reader, enabled: false)
        // Always restore the checkbox even if the originating HTTP task is
        // cancelled while the radio is being given time to disconnect.
        try? await Task<Never, Never>.sleep(nanoseconds: 750_000_000)
        setReaderEnabled(reader, enabled: true)
        try await connectThrowing(reader, pin: savedPin(for: reader))
    }

    func readerName(_ reader: BLEReader) -> String { "ble:\(reader.kind.rawValue)|\(reader.id.uuidString)" }

    /// Associates CoreBluetooth's private UUID with the card's real EID once
    /// GET EID succeeds. If iOS assigned a new UUID to a previously known card,
    /// merge the stale record into the currently connected reader.
    func recordEID(_ value: String, for reader: BLEReader) {
        let eid = value.uppercased()
        guard eid.range(of: "^[0-9A-F]{32}$", options: .regularExpression) != nil else { return }

        var saved = loadRememberedReaders()
        let duplicateIDs = Set(saved.compactMap { item in
            item.id != reader.id && item.eid?.uppercased() == eid ? item.id : nil
        })
        if !duplicateIDs.isEmpty {
            if duplicateIDs.contains(where: { enabledReaderIDs.contains($0) }) {
                enabledReaderIDs.insert(reader.id)
            }
            if savedPin(for: reader).isEmpty,
               let oldID = duplicateIDs.first(where: {
                   !(defaults.string(forKey: "nble.pin.\($0.uuidString)") ?? "").isEmpty
               }), let oldPIN = defaults.string(forKey: "nble.pin.\(oldID.uuidString)") {
                defaults.set(oldPIN, forKey: pinKey(for: reader))
            }
            for id in duplicateIDs {
                sessions[id]?.invalidate()
                if let old = readers.first(where: { $0.id == id }) {
                    central.cancelPeripheralConnection(old.peripheral)
                }
                sessions.removeValue(forKey: id)
                connectedReaderIDs.remove(id)
                connectingReaderIDs.remove(id)
                enabledReaderIDs.remove(id)
                authenticationBlockedReaderIDs.remove(id)
                defaults.removeObject(forKey: "nble.pin.\(id.uuidString)")
            }
            readers.removeAll { duplicateIDs.contains($0.id) }
        }

        saved.removeAll { $0.id == reader.id || duplicateIDs.contains($0.id) }
        saved.append(RememberedReader(id: reader.id, name: reader.name, kind: reader.kind, eid: eid))
        saveRememberedReaders(saved)
        defaults.set(enabledReaderIDs.map(\.uuidString).sorted(), forKey: Self.enabledReadersKey)
    }

    private func remember(_ reader: BLEReader) {
        var saved = loadRememberedReaders()
        let eid = saved.first(where: { $0.id == reader.id })?.eid
        saved.removeAll { $0.id == reader.id }
        saved.append(RememberedReader(id: reader.id, name: reader.name, kind: reader.kind, eid: eid))
        saveRememberedReaders(saved)
    }
    private func loadRememberedReaders() -> [RememberedReader] {
        guard let data = defaults.data(forKey: "remembered.readers") else { return [] }
        let decoded = (try? JSONDecoder().decode([RememberedReader].self, from: data)) ?? []
        var byID: [UUID: RememberedReader] = [:]
        var eidOwners: [String: UUID] = [:]
        for item in decoded {
            if let eid = item.eid?.uppercased(), let oldID = eidOwners[eid], oldID != item.id {
                byID.removeValue(forKey: oldID)
            }
            byID[item.id] = item
            if let eid = item.eid?.uppercased() { eidOwners[eid] = item.id }
        }
        return byID.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
    private func saveRememberedReaders(_ readers: [RememberedReader]) { defaults.set(try? JSONEncoder().encode(readers), forKey: "remembered.readers") }

    nonisolated private static func localIPv4Address() -> String? {
        var address: String?; var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }; defer { freeifaddrs(list) }
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let interface = pointer.pointee
            guard interface.ifa_addr.pointee.sa_family == UInt8(AF_INET), String(cString: interface.ifa_name) == "en0" else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            address = String(decoding: host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
        }
        return address
    }
}

enum BLEProfileError: Error { case reconnectRequired }
@MainActor protocol BLECardProfile: AnyObject { func prepare() async throws; func transmit(_ apdu: Data) async throws -> Data; func profileSwitchCommitted() async throws; func invalidate() }
extension BLECardProfile { func profileSwitchCommitted() async throws {}; func invalidate() {} }

@MainActor
final class NbleProfile: BLECardProfile {
    private unowned let transport: BLEDeviceSession; private let deviceName: String; private let password: String
    private let allowModeSwitch: Bool
    private var tx: CBCharacteristic!; private var counter = 0
    private var idleTask: Task<Void, Never>?
    private var transmitting = false
    init(transport: BLEDeviceSession, deviceName: String, password: String,
         allowModeSwitch: Bool = true) {
        self.transport = transport; self.deviceName = deviceName
        self.password = password; self.allowModeSwitch = allowModeSwitch
    }
    func prepare() async throws {
        guard let tx = transport.characteristic(CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")),
              let rx = transport.characteristic(CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")) else { throw RemobleError.message("NBLE UART characteristics not found") }
        self.tx = tx; transport.clearNotifications(); try await transport.enableNotifications(rx)
        let challenge = try await transport.receive(timeout: 5) { $0.count >= 17 && $0[0] == 0xE0 }
        let key = try NbleAuth.key(deviceName: deviceName, password: password)
        var plain = try NbleAuth.tripleDES(challenge.subdata(in: 1..<17), key: key, decrypt: true)
        let next = (((Int(plain[8]) << 8) | Int(plain[7])) + 1) & 0xffff; plain[7] = UInt8(next & 0xff); plain[8] = UInt8(next >> 8)
        try await transport.write(Data([0xE0]) + (try NbleAuth.tripleDES(plain, key: key, decrypt: false)), to: tx)
        let ack = try await transport.receive(timeout: 5) { $0.count >= 3 && $0[0] == 0xE0 }
        guard ack[1] == 0x90 && ack[2] == 0x00 else {
            if ack[1] == 0x6A && ack[2] == 0x86 { throw RemobleError.message("Wrong BLE card hexadecimal PIN") }
            throw RemobleError.message("NBLE authentication failed: \(ack.hex)")
        }
        counter = 0; let mode = try await transmit(try Data(hex: "FE0D000001"))
        if mode.first == 1, allowModeSwitch {
            _ = try? await transmit(try Data(hex: "FE0C020001")); throw BLEProfileError.reconnectRequired
        }
    }
    func transmit(_ apdu: Data) async throws -> Data {
        idleTask?.cancel()
        idleTask = nil
        while transmitting {
            try Task.checkCancellation()
            try await Task<Never, Never>.sleep(nanoseconds: 10_000_000)
        }
        transmitting = true
        defer {
            transmitting = false
            scheduleIdlePing()
        }
        return try await exchange(apdu)
    }

    func invalidate() {
        idleTask?.cancel()
        idleTask = nil
    }

    private func exchange(_ apdu: Data) async throws -> Data {
        let requestCounter = counter; counter = (counter + 1) & 0xff; transport.clearNotifications()
        for frame in BLEProtocolCodec.nbleFrames(counter: requestCounter, apdu: apdu) { try await transport.write(frame, to: tx) }
        var frames: [Data] = []; repeat { frames.append(try await transport.receive(timeout: 30)) } while (frames.last!.first! & 0x20) == 0
        let decoded = try BLEProtocolCodec.decodeNble(frames)
        guard decoded.counter == requestCounter else { throw RemobleError.message("NBLE counter mismatch") }; return decoded.apdu
    }

    private func scheduleIdlePing() {
        idleTask?.cancel()
        idleTask = Task { @MainActor [weak self] in
            try? await Task<Never, Never>.sleep(nanoseconds: 12_000_000_000)
            guard !Task.isCancelled, let self else { return }
            self.idleTask = nil
            _ = try? await self.transmit(try Data(hex: "99980000FB0000"))
        }
    }
}

@MainActor
final class BeeSimProfile: BLECardProfile {
    private unowned let transport: BLEDeviceSession; private var tx: CBCharacteristic!; private var commandCount = 0
    init(transport: BLEDeviceSession) { self.transport = transport }
    func prepare() async throws {
        let all = transport.allCharacteristics()
        tx = all.first { $0.uuid.uuidString.lowercased().contains("ae01") && !$0.properties.intersection([.write, .writeWithoutResponse]).isEmpty }
            ?? all.first { !$0.properties.intersection([.write, .writeWithoutResponse]).isEmpty }
        let rx = all.first { $0.uuid.uuidString.lowercased().contains("ae02") && !$0.properties.intersection([.notify, .indicate]).isEmpty }
            ?? all.first { $0 !== tx && !$0.properties.intersection([.notify, .indicate]).isEmpty }
        guard tx != nil, let rx else { throw RemobleError.message("BLE card characteristics not found") }
        try await transport.enableNotifications(rx); try await Task<Never, Never>.sleep(nanoseconds: 200_000_000)
        _ = try await exchange(try Data(hex: "A03E040000"))
    }
    func transmit(_ apdu: Data) async throws -> Data { try await exchange(apdu) }
    func profileSwitchCommitted() async throws {
        transport.clearNotifications(); let length = min(244, max(20, transport.maximumWriteLength))
        for frame in try BLEProtocolCodec.beeFrames(try Data(hex: "A03F000000"), payloadSize: length - 2) { try await transport.write(frame, to: tx) }
    }
    private func exchange(_ command: Data) async throws -> Data {
        if commandCount >= 21 { try await Task<Never, Never>.sleep(nanoseconds: 1_200_000_000); commandCount = 0 }
        commandCount += 1; transport.clearNotifications(); let length = min(244, max(20, transport.maximumWriteLength))
        for frame in try BLEProtocolCodec.beeFrames(command, payloadSize: length - 2) { try await transport.write(frame, to: tx) }
        var frames: [Data] = []; var expected = 0
        repeat { let frame = try await transport.receive(timeout: 100); guard frame.count >= 2 else { throw RemobleError.message("Truncated BLE card response") }; if expected == 0 { expected = Int(frame[0]) }; frames.append(frame) } while frames.count < expected
        return try BLEProtocolCodec.decodeBee(frames)
    }
}

@preconcurrency import CoreBluetooth
import Foundation

/// One independent CoreBluetooth transport. Keeping all mutable GATT state per
/// peripheral allows Remoble to keep several eUICCs connected concurrently.
@MainActor
final class BLEDeviceSession: NSObject, @preconcurrency CBPeripheralDelegate {
    let reader: BLEReader
    private let onRSSIUpdate: (Int) -> Void
    private(set) var profile: BLECardProfile?
    private(set) var settleUntil: Date?
    private var settleDeadline: Date?
    private var settleProbeGap: TimeInterval = 5

    private var services: [CBService] = []
    private var pendingCharacteristics = 0
    private var discoveryContinuation: CheckedContinuation<Void, Error>?
    private var discoveryToken: UUID?
    private var notifyContinuations: [CBUUID: CheckedContinuation<Void, Error>] = [:]
    private var notifyTokens: [CBUUID: UUID] = [:]
    private var writeContinuation: CheckedContinuation<Void, Error>?
    private var writeToken: UUID?
    private var notifications: [Data] = []

    init(reader: BLEReader, onRSSIUpdate: @escaping (Int) -> Void = { _ in }) {
        self.reader = reader
        self.onRSSIUpdate = onRSSIUpdate
        super.init()
        reader.peripheral.delegate = self
    }

    var isConnected: Bool { reader.peripheral.state == .connected && profile != nil }
    var isSettling: Bool { settleDeadline != nil }
    var settleProbeDelay: TimeInterval { max(0, settleUntil?.timeIntervalSinceNow ?? 0) }

    func discoverTransport() async throws {
        services.removeAll()
        notifications.removeAll()
        reader.peripheral.delegate = self
        let token = UUID()
        try await withCheckedThrowingContinuation { continuation in
            discoveryToken = token
            discoveryContinuation = continuation
            reader.peripheral.discoverServices(nil)
            Task { @MainActor [weak self] in
                try? await Task<Never, Never>.sleep(nanoseconds: 10_000_000_000)
                guard let self, self.discoveryToken == token else { return }
                self.finishDiscovery(RemobleError.message("BLE service discovery timed out"))
            }
        }
    }

    func prepare(pin: String, allowModeSwitch: Bool = true) async throws {
        let nextProfile: BLECardProfile
        switch reader.kind {
        case .nble:
            let password = pin.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? NbleAuth.defaultPassword(deviceName: reader.name) : pin
            nextProfile = NbleProfile(transport: self, deviceName: reader.name,
                                      password: password, allowModeSwitch: allowModeSwitch)
        case .tyBle:
            nextProfile = TYBLEProfile(transport: self, deviceName: reader.name)
        case .beeSim:
            nextProfile = BeeSimProfile(transport: self)
        case .estkme:
            nextProfile = ESTKmeProfile(transport: self)
        case .estkmeRed:
            nextProfile = ESTKmeRedProfile(transport: self)
        case .simLink:
            nextProfile = SimLinkProfile(transport: self)
        }
        try await nextProfile.prepare()
        profile = nextProfile
    }

    func invalidate(error: Error? = nil) {
        profile?.invalidate()
        profile = nil
        services.removeAll()
        notifications.removeAll()
        discoveryToken = nil
        discoveryContinuation?.resume(throwing: error ?? RemobleError.message("BLE reader disconnected"))
        discoveryContinuation = nil
        for continuation in notifyContinuations.values {
            continuation.resume(throwing: error ?? RemobleError.message("BLE reader disconnected"))
        }
        notifyContinuations.removeAll()
        notifyTokens.removeAll()
        writeToken = nil
        writeContinuation?.resume(throwing: error ?? RemobleError.message("BLE reader disconnected"))
        writeContinuation = nil
    }

    func beginSettling(seconds: TimeInterval) {
        settleUntil = Date().addingTimeInterval(seconds)
        // Match the Android Remoble recovery budget: 150 seconds total,
        // including the initial radio-silent window. Adding the quiet period
        // again can outlive NekokoLPA2's complete retry budget.
        settleDeadline = Date().addingTimeInterval(max(seconds, 150))
        settleProbeGap = 5
        profile?.invalidate()
        profile = nil
    }

    /// Returns true when probing may stop. A slow card gets up to 150 seconds
    /// from the beginning of recovery, matching Android Remoble behavior.
    func recordSettleProbe(ready: Bool) -> Bool {
        if ready || (settleDeadline.map { Date() >= $0 } ?? true) {
            endSettling()
            return true
        }
        settleUntil = Date().addingTimeInterval(settleProbeGap)
        settleProbeGap = min(settleProbeGap * 2, 20)
        return false
    }

    func endSettling() {
        settleUntil = nil
        settleDeadline = nil
        settleProbeGap = 5
    }

    func characteristic(_ uuid: CBUUID) -> CBCharacteristic? {
        services.lazy.compactMap(\.characteristics).flatMap { $0 }.first { $0.uuid == uuid }
    }

    func allCharacteristics() -> [CBCharacteristic] {
        services.compactMap(\.characteristics).flatMap { $0 }
    }

    func enableNotifications(_ characteristic: CBCharacteristic) async throws {
        let token = UUID()
        try await withCheckedThrowingContinuation { continuation in
            notifyTokens[characteristic.uuid] = token
            notifyContinuations[characteristic.uuid] = continuation
            reader.peripheral.setNotifyValue(true, for: characteristic)
            Task { @MainActor [weak self] in
                try? await Task<Never, Never>.sleep(nanoseconds: 8_000_000_000)
                guard let self, self.notifyTokens[characteristic.uuid] == token else { return }
                self.notifyTokens.removeValue(forKey: characteristic.uuid)
                self.notifyContinuations.removeValue(forKey: characteristic.uuid)?.resume(
                    throwing: RemobleError.message("BLE notification setup timed out"))
            }
        }
    }

    func write(_ data: Data, to characteristic: CBCharacteristic) async throws {
        guard reader.peripheral.state == .connected else { throw RemobleError.message("BLE is disconnected") }
        if characteristic.properties.contains(.write) {
            let token = UUID()
            try await withCheckedThrowingContinuation { continuation in
                writeToken = token
                writeContinuation = continuation
                reader.peripheral.writeValue(data, for: characteristic, type: .withResponse)
                Task { @MainActor [weak self] in
                    try? await Task<Never, Never>.sleep(nanoseconds: 8_000_000_000)
                    guard let self, self.writeToken == token else { return }
                    self.writeToken = nil
                    self.writeContinuation?.resume(throwing: RemobleError.message("BLE write timed out"))
                    self.writeContinuation = nil
                }
            }
        } else if characteristic.properties.contains(.writeWithoutResponse) {
            reader.peripheral.writeValue(data, for: characteristic, type: .withoutResponse)
            while !reader.peripheral.canSendWriteWithoutResponse {
                try Task.checkCancellation()
                try await Task<Never, Never>.sleep(nanoseconds: 5_000_000)
            }
        } else {
            throw RemobleError.message("BLE characteristic is not writable")
        }
    }

    func writeChunked(_ data: Data, to characteristic: CBCharacteristic, maxChunk: Int = 240) async throws {
        let writeType: CBCharacteristicWriteType = characteristic.properties.contains(.write)
            ? .withResponse : .withoutResponse
        let limit = max(20, min(maxChunk, reader.peripheral.maximumWriteValueLength(for: writeType)))
        for offset in stride(from: 0, to: data.count, by: limit) {
            try await write(data.subdata(in: offset..<min(offset + limit, data.count)), to: characteristic)
            if writeType == .withoutResponse {
                try await Task<Never, Never>.sleep(nanoseconds: 8_000_000)
            }
        }
    }

    func clearNotifications() { notifications.removeAll() }

    func receive(timeout: TimeInterval, matching predicate: (Data) -> Bool = { _ in true }) async throws -> Data {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let index = notifications.firstIndex(where: predicate) { return notifications.remove(at: index) }
            try Task.checkCancellation()
            try await Task<Never, Never>.sleep(nanoseconds: 20_000_000)
        }
        throw RemobleError.message("BLE response timed out")
    }

    var maximumWriteLength: Int {
        return reader.peripheral.maximumWriteValueLength(for: .withoutResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { finishDiscovery(error); return }
        services = peripheral.services ?? []
        pendingCharacteristics = services.count
        guard pendingCharacteristics > 0 else {
            finishDiscovery(RemobleError.message("No BLE services found")); return
        }
        for service in services { peripheral.discoverCharacteristics(nil, for: service) }
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        guard error == nil else { return }
        onRSSIUpdate(RSSI.intValue)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { finishDiscovery(error); return }
        pendingCharacteristics -= 1
        if pendingCharacteristics == 0 {
            discoveryToken = nil
            discoveryContinuation?.resume()
            discoveryContinuation = nil
        }
    }

    private func finishDiscovery(_ error: Error) {
        discoveryToken = nil
        discoveryContinuation?.resume(throwing: error)
        discoveryContinuation = nil
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        notifyTokens.removeValue(forKey: characteristic.uuid)
        guard let continuation = notifyContinuations.removeValue(forKey: characteristic.uuid) else { return }
        if let error, !isRecoverableGlocalWriteError(error) { continuation.resume(throwing: error) }
        else { continuation.resume() }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let continuation = writeContinuation else { return }
        writeToken = nil
        writeContinuation = nil
        if let error, !isRecoverableGlocalWriteError(error) { continuation.resume(throwing: error) }
        else { continuation.resume() }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let value = characteristic.value, error == nil { notifications.append(value) }
    }

    private func isRecoverableGlocalWriteError(_ error: Error) -> Bool {
        guard reader.name.localizedCaseInsensitiveContains("glocal") else { return false }
        let nsError = error as NSError
        let message = nsError.localizedDescription.lowercased()
        return nsError.code == CBATTError.Code.writeNotPermitted.rawValue
            || message.contains("writing is not permitted")
            || message.contains("write not permitted")
    }
}

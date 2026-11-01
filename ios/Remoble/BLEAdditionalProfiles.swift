@preconcurrency import CoreBluetooth
import Foundation

/// ISSC Transparent UART transport used by 9eSIM B1/B0 Plus and Tianyu
/// cards. Authentication and framing deliberately differ from legacy NBLE.
@MainActor
final class TYBLEProfile: BLECardProfile {
    private static let writeUUID = CBUUID(string: "49535343-8841-43F4-A8D4-ECBE34729BB3")
    private static let notifyUUID = CBUUID(string: "49535343-1E4D-4BD9-BA61-23C647249616")

    private unowned let transport: BLEDeviceSession
    private let deviceName: String
    private var tx: CBCharacteristic!

    init(transport: BLEDeviceSession, deviceName: String) {
        self.transport = transport
        self.deviceName = deviceName
    }

    func prepare() async throws {
        guard let tx = transport.characteristic(Self.writeUUID),
              let rx = transport.characteristic(Self.notifyUUID) else {
            throw RemobleError.message("TYBLE ISSC UART characteristics not found")
        }
        self.tx = tx
        transport.clearNotifications()
        try await transport.enableNotifications(rx)
        let response = try await exchange(
            TYBLEProtocol.authenticationAPDU(deviceName: deviceName), timeout: 10)
        guard response == Data([0x90, 0x00]) else {
            if response == Data([0x69, 0x82]) {
                throw RemobleError.message("TYBLE authentication rejected (SW=6982)")
            }
            throw RemobleError.message("TYBLE authentication failed: \(response.hex)")
        }
    }

    func transmit(_ apdu: Data) async throws -> Data {
        try await exchange(apdu, timeout: 100)
    }

    private func exchange(_ payload: Data, timeout: TimeInterval) async throws -> Data {
        transport.clearNotifications()
        try await transport.writeChunked(try TYBLEProtocol.frame(payload), to: tx, maxChunk: 512)

        var buffer = Data()
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            buffer += try await transport.receive(timeout: max(0.05, deadline.timeIntervalSinceNow))
            guard buffer.count >= 2 else { continue }
            let length = (Int(buffer[0]) << 8) | Int(buffer[1])
            guard buffer.count >= length + 2 else { continue }
            return buffer.subdata(in: 2..<(length + 2))
        }
        throw RemobleError.message("TYBLE response timed out")
    }
}

@MainActor
class ESTKmeTransportProfile: BLECardProfile {
    unowned let transport: BLEDeviceSession
    var tx: CBCharacteristic!

    init(transport: BLEDeviceSession) { self.transport = transport }

    func configureUART() async throws {
        guard let tx = transport.characteristic(CBUUID(string: "6D65")),
              let rx = transport.characteristic(CBUUID(string: "544B")) else {
            throw RemobleError.message("ESTKme characteristics not found")
        }
        self.tx = tx
        transport.clearNotifications()
        try await transport.enableNotifications(rx)
    }

    func prepare() async throws { fatalError("Implemented by subclass") }
    func transmit(_ apdu: Data) async throws -> Data { fatalError("Implemented by subclass") }
}

/// Original ESTKme BLE framing: command + UInt16-LE length + payload.
@MainActor
final class ESTKmeProfile: ESTKmeTransportProfile {
    override func prepare() async throws {
        try await configureUART()
        _ = try await exchange(command: 0x02, payload: Data("ESTKme".utf8))
        _ = try await exchange(command: 0x03, payload: Data([0x01, 0x01]))
    }

    override func transmit(_ apdu: Data) async throws -> Data {
        let response = try await exchange(command: 0x04, payload: apdu)
        guard response.count >= 3 else { throw RemobleError.message("Truncated ESTKme response") }
        return Data(response.dropFirst(3))
    }

    private func exchange(command: UInt8, payload: Data) async throws -> Data {
        guard payload.count <= Int(UInt16.max) else { throw RemobleError.message("ESTKme command is too large") }
        var request = Data([command, UInt8(payload.count & 0xff), UInt8((payload.count >> 8) & 0xff)])
        request += payload
        transport.clearNotifications()
        try await transport.writeChunked(request, to: tx)

        var response = Data()
        var expected: Int?
        while expected == nil || response.count < expected! {
            response += try await transport.receive(timeout: 15)
            if response.count >= 3 { expected = 3 + Int(response[1]) + (Int(response[2]) << 8) }
        }
        return Data(response.prefix(expected!))
    }
}

/// ESTKme RED framing uses CCID messages over the same BLE UART service.
@MainActor
final class ESTKmeRedProfile: ESTKmeTransportProfile {
    private static let headerLength = 10

    override func prepare() async throws {
        try await configureUART()
        if let response = try? await exchangeCCID(type: 0x6f, payload: Data([0xff, 0xff, 0xff, 0x00, 0x02, 0x00, 0x00])),
           response.count >= 2, response[0] == 0x61 {
            _ = try? await exchangeCCID(type: 0x6f, payload: Data([0xff, 0xff, 0xff, 0x01, response[1]]))
        }
        _ = try await exchangeCCID(type: 0x62, payload: Data([0x01]))
    }

    override func transmit(_ apdu: Data) async throws -> Data {
        try await exchangeCCID(type: 0x6f, payload: apdu)
    }

    private func exchangeCCID(type: UInt8, payload: Data) async throws -> Data {
        var request = Data([type])
        let count = UInt32(payload.count)
        request += Data([
            UInt8(count & 0xff), UInt8((count >> 8) & 0xff),
            UInt8((count >> 16) & 0xff), UInt8((count >> 24) & 0xff),
            0, 0, 0, 0, 0
        ])
        request += payload
        transport.clearNotifications()
        try await transport.writeChunked(request, to: tx)

        var response = Data()
        while true {
            response += try await transport.receive(timeout: 15)
            guard response.count >= Self.headerLength else { continue }
            let length = Int(response[1]) | (Int(response[2]) << 8)
                | (Int(response[3]) << 16) | (Int(response[4]) << 24)
            let expected = Self.headerLength + length
            if expected == Self.headerLength, response[7] & 0x80 != 0 {
                // CCID time extension: wait for the actual response.
                response.removeAll(keepingCapacity: true)
                continue
            }
            guard response.count >= expected else { continue }
            return response.subdata(in: Self.headerLength..<expected)
        }
    }
}

/// SimLink exposes JSON commands over Nordic UART.
@MainActor
final class SimLinkProfile: BLECardProfile {
    private unowned let transport: BLEDeviceSession
    private var tx: CBCharacteristic!

    init(transport: BLEDeviceSession) { self.transport = transport }

    func prepare() async throws {
        guard let tx = transport.characteristic(CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")),
              let rx = transport.characteristic(CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")) else {
            throw RemobleError.message("SimLink UART characteristics not found")
        }
        self.tx = tx
        try await transport.enableNotifications(rx)
        _ = try await exchange(["cmd": "APDU", "action": 2], retry: true)
        _ = try await exchange(["cmd": "APDU", "action": 0], retry: true)
        _ = try? await transmit(try Data(hex: "80AA00000AA9088100820101830107"))
    }

    func transmit(_ apdu: Data) async throws -> Data {
        var command = apdu
        if command.count == 5, command[1] == 0xc0, command[2] == 0,
           command[3] == 0, (command[4] == 0 || command[4] > 0x4f) {
            command[4] = 0x4f
        }
        let hex = try await exchange(["cmd": "APDU", "data": command.hex, "action": 1], retry: false)
        return try Data(hex: hex)
    }

    private func exchange(_ object: [String: Any], retry: Bool) async throws -> String {
        let request = try JSONSerialization.data(withJSONObject: object)
        transport.clearNotifications()
        try await transport.writeChunked(request, to: tx)

        var response = Data()
        while true {
            response += try await transport.receive(timeout: 15)
            guard let json = try? JSONSerialization.jsonObject(with: response) as? [String: Any] else { continue }
            if let error = json["error"] as? String {
                if error == "APDU channel not open!", !retry {
                    _ = try await exchange(["cmd": "APDU", "action": 0], retry: true)
                    return try await exchange(object, retry: true)
                }
                if retry { return "" }
                throw RemobleError.message("SimLink: \(error)")
            }
            return json["data"] as? String ?? ""
        }
    }
}

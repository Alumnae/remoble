import CommonCrypto
import Foundation

enum CardState { case ready, proactiveRefresh, settling, other }

enum BLEProtocolCodec {
    static func cardState(_ response: Data) -> CardState {
        guard response.count >= 2 else { return .other }
        let sw1 = response[response.count - 2]
        let sw2 = response[response.count - 1]
        if sw1 == 0x90 && sw2 == 0x00 { return .ready }
        if sw1 == 0x91 { return .proactiveRefresh }
        if sw1 == 0x69 && (sw2 == 0x85 || sw2 == 0x99) { return .settling }
        return .other
    }

    static func selectAid(_ aid: Data) throws -> Data {
        guard !aid.isEmpty, aid.count <= 255 else { throw RemobleError.message("Invalid AID length") }
        return Data([0x00, 0xA4, 0x04, 0x00, UInt8(aid.count)]) + aid + Data([0x00])
    }

    static func requestsProactiveRefresh(_ apdu: Data) -> Bool {
        guard apdu.count >= 8, apdu[1] == 0xE2, apdu[2] == 0x91 else { return false }
        let shortLength = Int(apdu[4])
        let offset: Int
        let length: Int
        if shortLength != 0 {
            offset = 5; length = shortLength
        } else {
            guard apdu.count >= 7 else { return false }
            offset = 7; length = (Int(apdu[5]) << 8) | Int(apdu[6])
        }
        guard length >= 6, offset + length <= apdu.count, apdu[offset] == 0xBF,
              apdu[offset + 1] == 0x31 || apdu[offset + 1] == 0x32,
              let root = berLength(apdu, at: offset + 2, limit: offset + length) else { return false }
        var cursor = root.next
        let end = min(cursor + root.length, offset + length)
        while cursor < end {
            let tag = apdu[cursor]
            guard let item = berLength(apdu, at: cursor + 1, limit: end), item.next + item.length <= end else { return false }
            if tag == 0x81, item.length == 1 { return apdu[item.next] != 0 }
            cursor = item.next + item.length
        }
        return false
    }

    private static func berLength(_ data: Data, at offset: Int, limit: Int) -> (length: Int, next: Int)? {
        guard offset < limit else { return nil }
        let first = Int(data[offset])
        if first < 0x80 { return (first, offset + 1) }
        let count = first & 0x7f
        guard count > 0, count <= 2, offset + 1 + count <= limit else { return nil }
        var value = 0
        for index in 0..<count { value = (value << 8) | Int(data[offset + 1 + index]) }
        return (value, offset + 1 + count)
    }

    static func nbleFrames(counter: Int, apdu: Data, maxWrite: Int = 101) -> [Data] {
        var body = Data([UInt8(counter & 0xff)])
        body += apdu
        let checksum = body.reduce(0) { ($0 + Int($1)) & 0xffff }
        body += Data([UInt8(checksum >> 8), UInt8(checksum & 0xff)])
        let size = maxWrite - 1
        return stride(from: 0, to: body.count, by: size).enumerated().map { index, start in
            let end = min(start + size, body.count)
            let final = end == body.count
            return Data([UInt8((final ? 0x20 : 0) | (index & 0x1f))]) + body[start..<end]
        }
    }

    static func decodeNble(_ frames: [Data]) throws -> (counter: Int, apdu: Data) {
        guard !frames.isEmpty else { throw RemobleError.message("Missing NBLE response") }
        var payload = Data()
        for (index, frame) in frames.enumerated() {
            guard let header = frame.first, Int(header & 0x1f) == index,
                  ((header & 0x20) != 0) == (index == frames.count - 1) else {
                throw RemobleError.message("Invalid NBLE response frame")
            }
            payload += frame.dropFirst()
        }
        guard payload.count >= 5 else { throw RemobleError.message("Truncated NBLE response") }
        let raw = payload.dropLast(2)
        let checksum = (Int(payload[payload.count - 2]) << 8) | Int(payload[payload.count - 1])
        guard raw.reduce(0, { ($0 + Int($1)) & 0xffff }) == checksum else {
            throw RemobleError.message("NBLE checksum mismatch")
        }
        return (Int(raw.first!), Data(raw.dropFirst()))
    }

    static func beeFrames(_ payload: Data, payloadSize: Int) throws -> [Data] {
        let total = max(1, (payload.count + payloadSize - 1) / payloadSize)
        guard total <= 255 else { throw RemobleError.message("BLE card command is too large") }
        return (0..<total).map { index in
            let start = index * payloadSize
            let end = min(start + payloadSize, payload.count)
            return Data([UInt8(total), UInt8(index + 1)]) + payload[start..<end]
        }
    }

    static func decodeBee(_ frames: [Data]) throws -> Data {
        guard let total = frames.first?.first, Int(total) == frames.count else {
            throw RemobleError.message("Incomplete BLE card response")
        }
        var output = Data()
        for (index, frame) in frames.enumerated() {
            guard frame.count >= 2, frame[0] == total, frame[1] == UInt8(index + 1) else {
                throw RemobleError.message("Invalid BLE card frame sequence")
            }
            output += frame.dropFirst(2)
        }
        return output
    }
}

enum NbleAuth {
    static func defaultPassword(deviceName: String) -> String {
        String(deviceName.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().suffix(6).reversed())
    }

    static func passwordHex(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.range(of: "^[0-9a-fA-F]{6}$", options: .regularExpression) != nil {
            return trimmed.uppercased()
        }
        var crc = 0xB704CE
        for byte in value.utf8 {
            crc ^= Int(byte) << 16
            for _ in 0..<8 {
                crc <<= 1
                if crc & 0x1000000 != 0 { crc ^= 0x1864CFB }
            }
        }
        return String(format: "%06X", crc & 0xffffff)
    }

    static func key(deviceName: String, password: String) throws -> Data {
        let identifier = String(deviceName.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().suffix(6))
        guard identifier.count >= 2 else { throw RemobleError.message("Invalid BLE card name") }
        let suffix = String(identifier.suffix(2))
        guard suffix.range(of: "^[0-9A-F]{2}$", options: .regularExpression) != nil else {
            throw RemobleError.message("BLE card name must end with two hexadecimal characters")
        }
        let base = suffix + passwordHex(password)
        let compact = try Data(hex: base + base + base + "00000000")
        return compact + compact.prefix(8)
    }

    static func tripleDES(_ input: Data, key: Data, decrypt: Bool) throws -> Data {
        guard input.count.isMultiple(of: kCCBlockSize3DES), key.count == kCCKeySize3DES else {
            throw RemobleError.message("Invalid 3DES input")
        }
        let outputCapacity = input.count + kCCBlockSize3DES
        var output = Data(count: outputCapacity)
        var moved = 0
        let status = output.withUnsafeMutableBytes { out in
            input.withUnsafeBytes { source in
                key.withUnsafeBytes { secret in
                    CCCrypt(decrypt ? CCOperation(kCCDecrypt) : CCOperation(kCCEncrypt),
                            CCAlgorithm(kCCAlgorithm3DES), CCOptions(kCCOptionECBMode),
                            secret.baseAddress, key.count, nil,
                            source.baseAddress, input.count,
                            out.baseAddress, outputCapacity, &moved)
                }
            }
        }
        guard status == kCCSuccess else { throw RemobleError.message("3DES failed (\(status))") }
        output.removeSubrange(moved..<output.count)
        return output
    }
}

/// Tianyu/TYBLE wire helpers used by 9eSIM B1/B0 Plus devices. These cards
/// advertise an eight-character UID and do not speak the legacy NBLE framing.
enum TYBLEProtocol {
    private static let deviceNamePattern =
        "^(?:[A-Za-z0-9]+-BT-([0-9A-Fa-f]{8})|9eSIM([0-9]{8}))$"

    static func isDeviceName(_ name: String) -> Bool {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
            .range(of: deviceNamePattern, options: .regularExpression) != nil
    }

    static func uid(from deviceName: String) throws -> String {
        let name = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let expression = try? NSRegularExpression(pattern: deviceNamePattern,
                                                        options: [.caseInsensitive]),
              let match = expression.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) else {
            throw RemobleError.message("TYBLE device name does not contain an eight-character UID")
        }
        for index in 1...2 where match.range(at: index).location != NSNotFound {
            guard let range = Range(match.range(at: index), in: name) else { continue }
            return String(name[range]).uppercased()
        }
        throw RemobleError.message("TYBLE device UID is missing")
    }

    static func pin(for deviceName: String) throws -> Data {
        let identifier = try uid(from: deviceName)
        let bytes = try stride(from: 0, to: identifier.count, by: 2).map { offset -> UInt8 in
            let start = identifier.index(identifier.startIndex, offsetBy: offset)
            let end = identifier.index(start, offsetBy: 2)
            guard let value = UInt8(identifier[start..<end], radix: 16) else {
                throw RemobleError.message("Invalid TYBLE device UID")
            }
            return value
        }
        let sum = bytes.reduce(0) { ($0 + Int($1)) & 0xff }
        let xor = bytes.reduce(0) { $0 ^ Int($1) }
        let mixed = (sum + xor) & 0xff
        var output = Data()
        for value in [sum, xor, mixed] {
            output.append(asciiDigit(value >> 4))
            output.append(asciiDigit(value & 0x0f))
        }
        return output
    }

    static func authenticationAPDU(deviceName: String) throws -> Data {
        Data([0xff, 0x60, 0x5a, 0x01, 0x06]) + (try pin(for: deviceName))
    }

    static func frame(_ payload: Data) throws -> Data {
        guard payload.count <= Int(UInt16.max) else {
            throw RemobleError.message("TYBLE command is too large")
        }
        return Data([UInt8(payload.count >> 8), UInt8(payload.count & 0xff)]) + payload
    }

    private static func asciiDigit(_ nibble: Int) -> UInt8 {
        UInt8(0x30 + (nibble <= 9 ? nibble : nibble - 6))
    }
}

import Foundation

enum RemobleError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        if case let .message(value) = self { return value }
        return nil
    }
}

extension Data {
    init(hex: String) throws {
        let value = hex.replacingOccurrences(of: " ", with: "")
        guard value.count.isMultiple(of: 2) else { throw RemobleError.message("Invalid hexadecimal value") }
        var output = Data(capacity: value.count / 2)
        var index = value.startIndex
        while index < value.endIndex {
            let end = value.index(index, offsetBy: 2)
            guard let byte = UInt8(value[index..<end], radix: 16) else {
                throw RemobleError.message("Invalid hexadecimal value")
            }
            output.append(byte)
            index = end
        }
        self = output
    }

    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

extension Array where Element == UInt8 {
    var data: Data { Data(self) }
}

import CommonCrypto
import Foundation
import Security

/// Card-level authorization used by the affected 9eSIM B0 family.
///
/// This is intentionally separate from the BLE PIN/handshake.  The caller
/// invokes it only after an ISD-R channel is open, after GET EID and GET
/// EUICC CHALLENGE, and immediately before AUTHENTICATE SERVER.
final class NineEsimCardAuthenticator {
    private final class RedirectBlocker: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            willPerformHTTPRedirection response: HTTPURLResponse,
            newRequest request: URLRequest,
            completionHandler: @escaping (URLRequest?) -> Void
        ) {
            completionHandler(nil)
        }
    }

    private static let keychainService = "ee.nekoko.remoble.card-auth"
    private static let endpointHost = "key.9esim.com"
    private static let b0LowerBound = "89086029202200002122000030900000"
    private static let b0UpperBound = "89086029202200002122000040000000"

    static func requiresAuthorization(eid: String) -> Bool {
        isEID(eid)
            && eid.compare(b0LowerBound) != .orderedAscending
            && eid.compare(b0UpperBound) == .orderedAscending
    }

    /// B1 identifiers are not covered by the supplied B0 range.  For a real
    /// 9eSIM reader we may still ask the same service once; authorization is
    /// injected only when the service actually returns a valid key.
    static func maySupportAuthorization(
        eid: String,
        readerName: String,
        usesNineEsimTransport: Bool = false
    ) -> Bool {
        guard isEID(eid) else { return false }
        if requiresAuthorization(eid: eid) { return true }
        return usesNineEsimTransport
            || readerName.range(of: "9esim", options: [.caseInsensitive]) != nil
    }

    /// Returns nil when the service has no key for an optional/unknown model.
    func request(eid: String, challenge: Data) async throws -> Data? {
        guard Self.isEID(eid), challenge.count == 16 else {
            throw RemobleError.message("Card authorization session is incomplete")
        }
        guard var key = try await key(for: eid) else { return nil }
        defer { key.resetBytes(in: 0..<key.count) }

        var nonce = Data(count: 16)
        let randomStatus = nonce.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!)
        }
        guard randomStatus == errSecSuccess else {
            throw RemobleError.message("Unable to generate card authorization nonce")
        }
        return try Self.buildRequest(psk: key, challenge: challenge, nonce: nonce)
    }

    /// BF71 18 80 04 <MAC4> 81 10 <nonce16>
    static func buildRequest(psk: Data, challenge: Data, nonce: Data) throws -> Data {
        guard psk.count == 16, challenge.count == 16, nonce.count == 16 else {
            throw RemobleError.message("Card authorization requires 16-byte values")
        }

        var seed = Data(challenge.prefix(8))
        seed += nonce.prefix(8)
        var derived = try aesCBCEncrypt(key: psk, input: seed)
        defer { derived.resetBytes(in: 0..<derived.count) }
        var encrypted = try aesCBCEncrypt(key: derived, input: challenge)
        defer { encrypted.resetBytes(in: 0..<encrypted.count) }

        let mac = Data((0..<4).map { encrypted[$0] ^ encrypted[$0 + 8] })
        var result = Data([0xBF, 0x71, 0x18, 0x80, 0x04])
        result += mac
        result += Data([0x81, 0x10])
        result += nonce
        return result
    }

    private func key(for eid: String) async throws -> Data? {
        if let cached = Self.loadKey(eid: eid) { return cached }

        var components = URLComponents()
        components.scheme = "https"
        components.host = Self.endpointHost
        components.path = "/psk"
        components.queryItems = [URLQueryItem(name: "eid", value: eid)]
        guard let url = components.url else {
            throw RemobleError.message("Unable to construct card authorization request")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 20
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        NSLog("Remoble requesting card authorization key for EID %@", eid)

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(
                for: request, delegate: RedirectBlocker())
        } catch {
            throw RemobleError.message("Unable to reach card authorization service")
        }
        guard data.count <= 8_192,
              let http = response as? HTTPURLResponse,
              http.url?.scheme?.lowercased() == "https",
              http.url?.host?.lowercased() == Self.endpointHost else {
            throw RemobleError.message("Invalid card authorization response")
        }
        NSLog("Remoble card authorization service returned HTTP %d for EID %@",
              http.statusCode, eid)
        guard http.statusCode == 200 else { return nil }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let value = object["psk"] as? String,
              Self.isHexKey(value),
              let decoded = try? Data(hex: value) else {
            throw RemobleError.message("Card authorization service returned an invalid key")
        }
        Self.saveKey(decoded, eid: eid)
        return decoded
    }

    private static func isEID(_ value: String) -> Bool {
        value.count == 32 && value.unicodeScalars.allSatisfy { (48...57).contains($0.value) }
    }

    private static func isHexKey(_ value: String) -> Bool {
        value.count == 32 && value.unicodeScalars.allSatisfy {
            (48...57).contains($0.value)
                || (65...70).contains($0.value)
                || (97...102).contains($0.value)
        }
    }

    private static func account(eid: String) -> String { "b0-psk:\(eid)" }

    private static func loadKey(eid: String) -> Data? {
        if let data = UserDefaults.standard.data(forKey: account(eid: eid)), data.count == 16 {
            return data
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account(eid: eid),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data, data.count == 16 else { return nil }
        UserDefaults.standard.set(data, forKey: account(eid: eid))
        return data
    }

    private static func saveKey(_ key: Data, eid: String) {
        UserDefaults.standard.set(key, forKey: account(eid: eid))
    }

    private static func aesCBCEncrypt(key: Data, input: Data) throws -> Data {
        guard key.count == kCCKeySizeAES128,
              !input.isEmpty,
              input.count.isMultiple(of: kCCBlockSizeAES128) else {
            throw RemobleError.message("Invalid AES card authorization input")
        }

        let iv = Data(repeating: 0, count: kCCBlockSizeAES128)
        var output = Data(count: input.count)
        let outputSize = output.count
        var moved = 0
        let status = key.withUnsafeBytes { keyBytes in
            iv.withUnsafeBytes { ivBytes in
                input.withUnsafeBytes { inputBytes in
                    output.withUnsafeMutableBytes { outputBytes in
                        CCCrypt(
                            CCOperation(kCCEncrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(0),
                            keyBytes.baseAddress, key.count,
                            ivBytes.baseAddress,
                            inputBytes.baseAddress, input.count,
                            outputBytes.baseAddress, outputSize,
                            &moved
                        )
                    }
                }
            }
        }
        guard status == kCCSuccess, moved == input.count else {
            throw RemobleError.message("Unable to calculate card authorization")
        }
        return output
    }
}

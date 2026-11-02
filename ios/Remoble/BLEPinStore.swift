import Foundation
import Security

/// Keychain-backed per-reader BLE PIN storage with one-time UserDefaults migration.
enum BLEPinStore {
    private static let service = "ee.nekoko.remoble.ble-pin"

    static func load(for id: UUID, legacyDefaults: UserDefaults = .standard) -> String {
        let account = id.uuidString
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
           let data = result as? Data, let value = String(data: data, encoding: .utf8) {
            return value
        }

        let legacyKey = "nble.pin.\(account)"
        let legacy = legacyDefaults.string(forKey: legacyKey) ?? ""
        legacyDefaults.removeObject(forKey: legacyKey)
        if !legacy.isEmpty { try? save(legacy, for: id) }
        return legacy
    }

    static func save(_ pin: String, for id: UUID) throws {
        let normalized = pin.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !normalized.isEmpty else { delete(for: id); return }
        let identity: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: id.uuidString
        ]
        let values: [String: Any] = [
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: Data(normalized.utf8)
        ]
        var status = SecItemUpdate(identity as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(identity.merging(values) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else {
            throw RemobleError.message("Unable to protect the BLE PIN in Keychain (\(status))")
        }
    }

    static func delete(for id: UUID) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: id.uuidString
        ]
        SecItemDelete(query as CFDictionary)
    }
}

import Foundation
/// UserDefaults-backed per-reader BLE PIN storage. This matches the pre-privacy behavior and does
/// not make reconnecting depend on Keychain availability while the app is waking in background.
enum BLEPinStore {
    static func load(for id: UUID, legacyDefaults: UserDefaults = .standard) -> String {
        legacyDefaults.string(forKey: key(for: id)) ?? ""
    }

    static func save(_ pin: String, for id: UUID) throws {
        let normalized = pin.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !normalized.isEmpty else { delete(for: id); return }
        UserDefaults.standard.set(normalized, forKey: key(for: id))
    }

    static func delete(for id: UUID) {
        UserDefaults.standard.removeObject(forKey: key(for: id))
    }

    private static func key(for id: UUID) -> String { "nble.pin.\(id.uuidString)" }
}

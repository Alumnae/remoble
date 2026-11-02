# Remoble Privacy Notice

Last updated: 2 November 2026

Remoble is designed to operate locally. It does not include advertising, analytics, tracking SDKs, user accounts, or telemetry.

## Data processed on the device

Remoble processes nearby Bluetooth device names, platform identifiers, signal strength, connection status, smart-card commands and responses, and locally configured bridge settings. Remembered Bluetooth PINs and remote-access passwords are protected using Android Keystore or Apple Keychain where supported. Card authorization keys are also stored in platform-protected storage.

The Android application disables cloud backup of its application data. On iOS, protected credentials use Keychain items marked as available only on the current device.

## Network communication

Remoble runs a local HTTP service so clients chosen by the user can communicate with selected Bluetooth readers. The service is intended for trusted local networks. Users can enable a remote password, which protects compatible requests and responses.

For compatible cards that require additional authorization, Remoble sends that card's 32-character identifier over HTTPS to `key.9esim.com/psk` to request the card-specific authorization key. This happens only when that authorization is required for the requested card operation. Remoble does not send the phone's advertising identifier, contacts, location, photos, or account information to that service. The returned key is stored in protected local storage.

`key.9esim.com` is operated by a third party. Its operator is responsible for its own server-side retention and privacy practices.

## Permissions

- Bluetooth permissions are used to discover and communicate with compatible nearby readers.
- Local network and Internet access are used for the user-controlled local bridge and the conditional card-authorization request described above.
- Android foreground-service, wake-lock, and boot permissions keep an explicitly enabled bridge available.
- iOS background Bluetooth and audio modes support the features shown in the app.

Remoble does not use Bluetooth scanning to derive physical location. Android declares Bluetooth scanning as `neverForLocation` on supported versions.

## Retention and deletion

Settings and remembered readers remain on the device until the user removes them or uninstalls the app. Forgetting a reader deletes its stored PIN. Uninstalling the app removes its application data; platform Keychain/Keystore behavior is controlled by the operating system.

## Contact and changes

Privacy-related issues can be reported through the project's GitHub issue tracker: <https://github.com/Alumnae/remoble/issues>.

Changes to this notice are published in the repository history.

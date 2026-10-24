# Remoble

Android BLE-to-HTTP bridge for remote eUICC management from NekokoLPA2.

Remoble scans for a supported Bluetooth Low Energy eUICC reader, connects to it as a BLE
central, translates ISO 7816 APDUs to the reader's GATT protocol, and exposes the existing
RemoCard v2 HTTP API. The preferred port is `33777`; if it is already occupied (for example by
RemoCard), Remoble selects and remembers the next free port and shows it in the app. NekokoLPA2
can therefore use the reader locally or from another device through its **Remote / RemoCard**
adapter.

## Supported BLE protocols

- SimLink / `eSIM_Writer` (Nordic UART Service, JSON APDU framing)
- NBLE / 9eSIM and compatible GlocalMe readers (Nordic UART Service, authenticated binary APDU
  framing)
- ESTKme (RED length-prefixed framing)
- ESTKme RED (CCID-over-BLE framing)

The scanner also identifies devices by their advertised GATT service, so a reader does not
have to expose a stable Bluetooth name. BeeSIM uses a different protocol and is not enabled yet.

## Android and root

Root is **not required** for this bridge. Remoble uses Android's public BLE central/GATT APIs
and asks for the Nearby devices permissions (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`) on
Android 12+, or location permission on older Android versions. It does not access the phone's
internal eUICC, OMAPI or Telephony APIs.

If by “USB interno” you mean the phone's built-in Bluetooth controller, Android exposes it via
the Bluetooth framework rather than as a USB device. An app cannot and does not need to claim
that internal controller through `UsbManager`. External USB readers are a separate transport.

## Use from NekokoLPA2

1. Install Remoble on the Android phone that is physically near the BLE reader.
2. Grant Nearby devices permission and turn on Bluetooth.
3. For an NBLE/9eSIM reader, enter its six-character hexadecimal PIN in **BLE eUICC PIN**. If
   the factory PIN is unchanged, leave the field empty: Remoble derives the default from the
   device name using the same rule as NekokoLPA2 (the last six characters in reverse order).
4. Start the server and wait for the BLE reader to appear in **Allowed readers**.
5. Note the actual listening port shown by Remoble. It may be `33778` when RemoCard already uses
   `33777`.
6. In NekokoLPA2 add a Remote / RemoCard endpoint:
   - on the same phone: `http://127.0.0.1:PORT`
   - from another device on the trusted LAN/VPN: `http://PHONE_IP:PORT`
7. Select the slot whose name begins with `ble:nble:`.

After the first successful GATT connection, Remoble stores the reader address, display name and
protocol type. On later launches the foreground service tries that reader again even before a new
advertisement is received. The NBLE PIN is stored only in the app's private preferences and reused
for its challenge-response authentication. This protocol-level PIN is separate from Android's
optional operating-system BLE bonding prompt.

If every AID supplied to `/openChannel` fails, Remoble treats the connection as potentially stale:
it closes the current GATT/profile state, reconnects and authenticates the remembered BLE reader,
then retries the complete AID list once. A second failure is returned normally, so an unsupported
AID cannot cause an infinite reconnect loop.

The HTTP service is unencrypted. Set a connection password and use a trusted LAN/VPN; do not
expose the displayed listening port directly to the public internet.

## Signing

Signing material is intentionally not stored in the repository. A normal debug build uses the
standard Android debug key. To use a specific keystore, define `REMOBLE_SIGNING_STORE_FILE`,
`REMOBLE_SIGNING_STORE_PASSWORD`, `REMOBLE_SIGNING_KEY_ALIAS` and
`REMOBLE_SIGNING_KEY_PASSWORD` as Gradle properties in the local Gradle user home.

## Architecture

```text
BLE eUICC reader <-> Android GATT <-> Remoble <-> RemoCard HTTP v2 <-> NekokoLPA2
```

The original OMAPI implementation remains in the source tree for comparison, but the running
application instantiates `BleSmartcardService` only.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

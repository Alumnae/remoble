# Remoble for iOS

The iOS target exposes a selected external BLE eUICC to NekokoLPA2 through the
same RemoCard v2 HTTP endpoints used by the Android app.

Supported in the native implementation:

- Multiple concurrent 9eSIM, GlocalMe, BeeSIM, ESTKme, ESTKme RED and SimLink
  connections. Every paired reader is
  remembered independently, restored by CoreBluetooth, and reconnected with
  exponential backoff when it becomes reachable again.
- A persistent checkbox whitelist controls which discovered BLE readers are
  remembered and exposed through `/listSlots`; unchecked readers are neither
  visible to remote clients nor automatically reconnected.
- 9eSIM/NBLE discovery, remembered device identifier and PIN, 3DES handshake,
  eUICC mode switch, APDU framing, and reconnect.
- Current 9eSIM profile-switch recovery: 20 seconds of radio silence followed
  by minimal CAT `STATUS` probes with exponential backoff for up to 150
  seconds, without forcing writer mode during the card reset.
- BeeSIM discovery, AE01/AE02 characteristic selection, packet framing, command
  pacing, and APDU transport.
- ESTKme legacy framing, ESTKme RED CCID-over-BLE (including time-extension
  frames), and SimLink JSON-over-Nordic-UART transport.
- GlocalMe's recoverable iOS `Writing is not permitted` behavior is accepted
  for characteristic and notification setup, matching NekokoLPA2 and avoiding
  a false transport stall after the card has accepted the GATT operation.
- `/listSlots`, `/openChannel`, `/sendApdu`, `/sendRawApdu`, `/closeChannel`, and
  `/listChannels` on TCP port 33777.
- Real ISO 7816 logical-channel allocation (`MANAGE CHANNEL`), CLA mapping,
  channel-aware GET RESPONSE, and physical channel cleanup for remote APDUs.
- Transparent 9eSIM card-level BF71 authorization on the already-open ISD-R
  channel. Remoble records GET EID and the one-time GET EUICC CHALLENGE,
  obtains a PSK from `https://key.9esim.com/psk?eid=...`, stores it as a
  device-only Keychain item, and sends BF71 immediately before AUTHENTICATE
  SERVER. A client that already sends BF71 is detected and is never
  authenticated twice. The observed B0 EID range is fail-closed; other 9eSIM
  models use the exchange only when the service returns a valid key.
- If every AID supplied to `/openChannel` fails, Remoble automatically cycles
  that reader's saved checkbox off and on, rebuilds its BLE session with the
  remembered PIN, and retries the complete AID list once.
- Remote ownership is identified by client IP or an optional stable
  `X-Remoble-Client` header. It lasts from `/openChannel` through
  `/closeChannel`, so another client's discovery or APDU request cannot close
  a channel between two steps of an active profile download. Same-client
  requests may use different readers in parallel; a competing client receives
  `409 reader_busy` until the owner releases all channels.
- Optional password protection compatible with NekokoLPA2's RemoCard v2
  client: `Authorization: Bearer` authentication plus AES-256-CBC encrypted
  request and response envelopes. The password is stored only in the iOS
  Keychain and must contain at least eight characters.

Open `Remoble.xcodeproj`, select an iPhone signing team, and run on a physical
iPhone. Point NekokoLPA2's remote reader URL to the URL shown in Remoble.
When Remote security is enabled, configure the same password in NekokoLPA2's
remote reader settings; unauthenticated clients receive HTTP 401 and cannot
access reader discovery or APDU endpoints.
The deployment target is iOS 15.6, matching NekokoLPA2 itself, and the project
builds in Swift 6 mode with current iPhoneOS SDKs.

There is no Remoble idle timeout: the HTTP listener stays bound and remembered
BLE readers remain connected while iOS lets the process run. The
`bluetooth-central` background mode and CoreBluetooth state restoration allow
BLE events and reconnects while the screen is off. Active HTTP requests also
use an iOS background task so they can finish if the app moves to the
background. Every received HTTP request immediately wakes/reconnects all
remembered BLE readers; reader-specific commands wait for the selected BLE
transport before sending an APDU.

iOS does not offer a supported background mode for an always-on arbitrary TCP
server. After iOS suspends the process, Bluetooth can wake it for BLE events,
but an incoming LAN HTTP connection alone cannot wake it. For uninterrupted
remote access, keep Remoble foreground-active (Guided Access is suitable for a
dedicated bridge phone), or use the Android service where continuous
foreground-service hosting is supported.

The main **Remoble Sounds** screen is a complete ambient-noise player with
generated white, pink and brown noise plus soft rain, selectable atmospheres,
volume, sleep timer, lock-screen metadata, and media play/pause commands. Audio
continues when the screen locks and, while the user is listening, also lets the
process service HTTP. The Bridge tab transparently contains the BLE/RemoCard
configuration and diagnostics. Calls, Low Power Mode, thermal pressure, force
quit, or process termination can still interrupt background work.

The BLE protocol behavior was validated against the MIT-licensed NekokoLPA2
implementation: <https://github.com/iebb/NekokoLPA2>.

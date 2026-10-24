# Android BLE bridge notes

## Does it need root?

No. An ordinary Android application can scan for BLE peripherals and open a GATT connection
through `BluetoothLeScanner` and `BluetoothGatt`. On Android 12 and newer the user grants the
Nearby devices runtime permissions `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`. On older releases,
BLE scanning requires location permission.

Official references:

- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner
- https://developer.android.com/reference/android/bluetooth/BluetoothGatt

The phone's integrated Bluetooth controller is not made available to apps as an internal USB
device. Android owns the controller and exposes it through the Bluetooth framework. Trying to
claim the controller through `UsbManager` would be the wrong layer and would normally be blocked
by the OS.

An *external* USB BLE dongle or CCID reader is different. Android USB host mode can enumerate it
and an app can ask the user for per-device permission without root, provided the phone supports
USB host/OTG and the app implements the device protocol:

- https://developer.android.com/develop/connectivity/usb/host

Remoble does not need that USB path for the phone's built-in BLE radio.

## Compatibility bridge

NekokoLPA2 already includes a RemoCard remote adapter. Therefore the compatibility boundary is
the existing RemoCard HTTP v2 API, not emulation of a USB device:

1. Remoble scans and identifies supported BLE eUICC readers.
2. It connects as the GATT central and maps BLE framing to raw ISO 7816 APDUs.
3. Each discovered peripheral is exposed as a `SmartcardReader` named `ble:<type>:<name>|<mac>`.
4. The original `/listSlots`, `/openChannel`, `/sendApdu`, `/sendRawApdu`, and `/closeChannel`
   endpoints remain compatible with NekokoLPA2.
5. NekokoLPA2 connects to the Android phone over LAN/VPN and sees the BLE peripheral as a remote
   RemoCard slot.

## Current protocol coverage

The implementation mirrors the corresponding NekokoLPA2 adapters:

- SimLink / `eSIM_Writer`: Nordic UART Service and JSON APDU commands.
- ESTKme: command + little-endian length framing.
- ESTKme RED: CCID messages transported over the RED GATT service.

BeeSIM, 9eSIM, and GlocalMe need their own framing/state machines before they can be advertised as
supported. Scanning them without a working APDU transport would create a misleading remote slot,
so Remoble intentionally filters them out for now.

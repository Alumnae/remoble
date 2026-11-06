# Remoble Privacy Policy

Effective date: 6 November 2026

ISEMI SRL ("ISEMI", "we", "us") provides Remoble. This policy explains how
the iOS and Android applications handle information when you use them.

## Information handled by the app

Remoble is designed to operate primarily on your device and on your local
network. It does not require an account, display advertising, analytics SDK,
tracking SDK, or a developer-operated telemetry service.

The app can store the following information locally on your device:

- selected Bluetooth accessory identifiers, names, connection preferences and
  optional access credentials you choose to save;
- local bridge configuration, including the listening port and an optional
  remote-access password;
- audio preferences, such as the selected sound, volume and timer; and
- a cached authorization key when a compatible card requires it.

This locally stored information is used only to provide the app's features. It
is not sold, used for advertising, or used to track you across apps or sites.

## Card authorization service

For a limited family of compatible Bluetooth accessories, Remoble may need to
request a card authorization key before performing an operation. In that case,
the app sends the accessory's EID (a 32-digit card identifier) over HTTPS to
`https://key.9esim.com/psk`. The request is made only when this authorization
is required. The resulting key is stored locally on your device for the same
accessory.

The EID is used solely to obtain the authorization key needed for that card.
Remoble does not use it for advertising, profiling, or cross-app tracking.

## Bluetooth and local network

Bluetooth permission is used to discover and connect to nearby compatible
accessories. Local Network permission is used to make selected accessories
available to compatible clients on the same network, and to control another
Remoble installation when you configure it to do so. These communications stay
within the network you select unless the card authorization service described
above is used.

## Sharing and retention

We do not sell personal data. Aside from the card authorization request
described above, Remoble does not send your information to ISEMI. Locally saved
preferences and cached authorization data remain on your device until you
remove the associated accessory, clear the app's data, or uninstall the app.

## Security

Where supported by the operating system, saved sensitive settings are protected
using platform security services. You are responsible for keeping any password
you configure for local remote access confidential.

## Your choices

You can revoke Bluetooth or Local Network permission in your device settings.
You can remove saved accessories in the app, and you can delete all locally
stored app data by uninstalling the app or clearing its data through your
device settings.

## Contact

For privacy questions, contact ISEMI SRL at [isemi@isemi.srl](mailto:isemi@isemi.srl).

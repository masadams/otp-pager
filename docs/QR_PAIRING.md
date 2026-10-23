# Android 0.6: setup QR

On the first station, enter the relay address and access token once and tap Save server settings.
The app creates the setup secret automatically. Link → Show setup QR displays
a black-and-white QR for the opposite station. Confirm before showing it; the
screen closes after 60 seconds or when the app leaves the foreground.

On a NEW other station, Link → Scan setup QR opens the embedded scanner. Camera
permission is requested only when scanning. No separate scanner app, Google Play
Services, online QR generator, or relay upload is needed. Review the relay host,
recipient role and pair-check fingerprint, then Use this setup to save the link.
Credentials stay masked. Receiving from A assigns B, and receiving from B assigns A.
Scanning alone or cancelling does not save settings or reset counters. Confirming
Use this setup saves the link; it cannot change an existing pairing. QR import is disabled once a pairing has been saved.

Devices without cameras can use Import QR image through Android's document picker,
or keep entering settings manually. Photos must show an upright, clear QR. Image
input is limited to 4 MB, dimensions/pixel count are bounded, and decoding is local.
The app intentionally blocks screenshots of its secret QR; an externally taken
photo is still a copy of the secrets and must be protected and deleted after use.
Camera hardware/autofocus are optional installation features; actual lens capture
and e-ink refresh behavior still need testing on physical target devices.

The QR contains the HTTPS relay address, relay access token, pairing secret and
recipient station role. It does NOT include pad material, consumed-slot counters,
message history, or private/public device identity keys. After saving the link,
physically transfer the pad through the Pad tab and exchange/verify device identity
cards through Device as before. A megabyte pad does not fit into a single QR.

Treat the setup QR as a bearer secret. Base64 is encoding, not encryption. Anyone
who photographs it obtains the token and pairing secret. Display it only in person
to the intended second station; do not upload it, use an online decoder, or make an
extra copy of an existing operational station. Replacing/reinstalling a device
requires a fresh pairing secret AND fresh pad, not rescanning its old setup.
The same offline trust rules as manually exchanging the secret apply: this QR has
no server signature, enrollment revocation, expiry enforced on the receiving side,
or proof that a displayed address belongs to a trustworthy operator. Its 60-second
screen timeout does not invalidate photographed credentials.

Payload: `FIELDPAIR1:` followed by canonical, unpadded Base64url of Java
DataOutputStream.writeUTF(endpoint), writeUTF(token), writeUTF(secret), then an
ASCII role byte. Inputs are strictly bounded and validated with the same HTTPS,
token and secret rules as manual setup; trailing data and unknown versions are
rejected. The wire is never registered as an Android deep link and is never logged
or copied automatically to the clipboard. Pending results are held only in memory;
process death may require rescanning. Preview and scan windows use FLAG_SECURE.

QR encode/decode: JourneyApps ZXing Android Embedded 4.3.0 (Apache-2.0), with its
ZXing core dependency. See THIRD_PARTY_NOTICES.md. QR generation uses a quiet zone,
medium error correction, black/white pixels and no animation or expiry countdown.
Tests cover setup round trips, invalid/truncated inputs, offline QR image round
trips, opposite-role export, unchanged vault/counters and review-before-save UI.

## Automatic pairing over USB (experimental hardware support)

Link → Pair devices opens a single cable wizard. Tap Save server settings on
at least one unused station, open the wizard on both, connect a USB DATA cable,
approve Android's USB permissions, and compare/confirm one complete code on BOTH
screens. The USB host automatically creates a fresh pairing secret and pad,
assigns roles, transfers settings/pad, exchanges Keystore public identities and
checks signed confirmations. No pad export/import, station-role entry, repeated
fingerprint entry, or separate readiness exchange is required. All 127 slots are
available afterward. The relay is never involved in this physical exchange.

The wizard only exchanges data from an empty station or saved relay settings with
no pad/counters/traffic. If the current station is already paired, Pair devices
first asks explicitly whether to replace its conversation and queued messages.
Approving keeps the relay address/token, creates a fresh setup secret and clears
the old conversation; the pad retirement ledger and device key remain intact.
Cancelling preserves the old pairing. This avoids retyping server details while
ensuring the old operational pad is never cloned or reused. A replacement device
still requires a fresh pairing on BOTH intended stations.

Android Open Accessory (AOA) is used through standard UsbManager host/accessory
APIs, without root or ADB. One device must negotiate USB host/OTG and power the bus;
the other must support AOA accessory mode. A charge-only cable will not work.
USB permission prompts are controlled by Android. Host/accessory support is
optional on Android, particularly on e-ink hardware. The transport is implemented
but has NOT been tested on physical USB devices here: virtual duplex-stream tests
verify the exchange, not actual cable compatibility, USB role switching or power.
Failure to negotiate is reported and the manual/QR path remains available.

The cable carries secrets directly. Use a trusted physical data cable and devices,
not an untrusted USB hub/computer. The one 128-bit displayed confirmation code is
SHA-256 of the domain-separated, length-prefixed identity transcript (both public
keys, both random nonces and the selected relay endpoint), truncated to 16 bytes
and displayed as eight hex groups. Compare every group. The relay token, fresh
secret and pad are sent only AFTER both people confirm and both device acceptance
signatures verify. A separate signed material transcript binds those verified keys,
nonces, endpoint, credentials and complete pad bundle; the application verifies
it automatically. ECDSA signatures use separate accept/server/setup/saved/complete
labels. This is a project-specific, unaudited physical-exchange protocol, not a
claim of a reviewed network key-exchange protocol. A malicious cable can observe
the directly transferred secrets; code comparison does not encrypt the cable.

Exposed pad IDs are durably retired on both devices before activation; retries
use fresh secrets and pad material. A rejected/cancelled stage does not activate a
station. Once both people confirm, the receiver commits a disabled pending vault,
the host verifies that signed acknowledgement and commits a pending vault, and a
signed completion enables messaging locally. This is not a distributed atomic
commit: unplugging or storage failure at the last step can leave one side paired
and the other incomplete. Incomplete vaults cannot send or sync; the UI instructs
a fresh pairing on BOTH rather than reusing the exposed material. Neither app
silently rolls counters back. Keep both apps foreground during the wizard; leaving
the screen aborts the transfer and closes USB handles. Setup, USB and scan windows
block screenshots. Existing message/pad/device controls remain under Link/Pad/Device
for diagnostics and fallback; the cable flow handles all those setup tasks itself.

## Identity QR fallback

Device → Show identity QR / Scan other device identity avoids copying public card
text when using the manual setup path. A `FIELDDEVICE1:` QR contains only the
public identity card. Scanning fills the peer-card field; the existing full
fingerprint comparison and immutable pinning are still required. The USB wizard
combines this identity exchange and pad verification into its one confirmation.

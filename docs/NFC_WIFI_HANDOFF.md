# Experimental NFC-assisted Wi-Fi pairing (v0.12)

> **Status: paused, shown as "Coming soon".** Field testing could not complete a pairing, so every
> entry point (Pair nearby, Renew nearby, Tap + Wi-Fi, Tap setup · NFC) is disabled behind
> `NEARBY_PAIRING_AVAILABLE` in `RadioPairActivity.kt`. Pair by USB cable. The screens and protocol
> below are unchanged and can be re-enabled by flipping that flag once these are addressed:
>
> - **Phone-to-phone NFC.** The sharing phone emulates a card (HCE) while its own NFC controller keeps
>   polling, which many phones handle poorly when two phones touch. It does not ask Android to prefer
>   its card service while in the foreground (`CardEmulation.setPreferredService`), so another
>   wallet or "other" service can take the tap. Android 15's `NfcAdapter.setDiscoveryTechnology` can
>   also stop the sharer polling during the tap.
> - **Wi-Fi reachability.** Many home, mesh and ISP routers isolate Wi-Fi clients from each other,
>   so the reader's connection to the sharer's local port times out even on the same network. The
>   error shown is a raw socket timeout rather than an explanation.

Optional local wireless pad transfer; USB remains available. Both devices need v0.12, NFC, and the same private IPv4 Wi-Fi. One must support Android host card emulation. Guest access-point isolation, IPv6-only Wi-Fi, VPN policies, or device firmware can prevent connections. Bluetooth, Wi-Fi Direct, automatic hotspot, Internet pad relay, and background listeners are not implemented.

## Use

Save relay settings on at least one unused chat. Open **Pair nearby · NFC + Wi-Fi** on both devices. Select 1/2/4 MiB directly in the wizard if desired, then choose Share on one and Read on the other and touch their NFC antennas. Review the local connection on the reader. Compare ALL confirmation-code groups on both screens and approve both. The sharing device's choice wins; the reader reviews it with the confirmation code. Initial size comes from chat settings (4 MiB by default).

For an existing friend choose **Renew nearby** for the same chat on both. Identity pins and chat secrets must match. Outstanding texts are confirmed before renewal; retained history and identities stay in place. Interrupted renewal uses the USB protocol's persistent paused/recovery states. If fresh pairing is interrupted after either side saves, replace that pairing on BOTH before use. Exposed candidates are retired, never cloned. Leaving/locking the transfer screen cancels the listener and connection. Recovery never copies an operational used pad.

## Protocol and threat model

1. The explicit sharing screen binds a TCP listener to one private IPv4 Wi-Fi interface and an ephemeral port. The NFC offer lasts at most 60 seconds; the wizard has a three-minute deadline. Successful mutual authentication closes the listener. Leaving closes every socket. No scanning, public-address listener, port forwarding, or permanent daemon. LAN attackers can still discover the port and cause denial of service.
2. The NFC ticket contains a protocol prefix, numeric local address, port, random 128-bit offer ID and independent random 256-bit secret. No pad, relay token, chat secret or private identity key. RAM only, never logged/persisted; reachable byte arrays are cleared on exit/use. Android/framework/string/GC copies cannot be guaranteed erased.
3. Both TCP peers contribute fresh 32-byte nonces. SHA-256 of the domain-separated ticket context (address/port/offer ID) and ordered nonces is the transcript. The reader proves the NFC secret with HMAC-SHA256 over transcript plus `reader`, then the sharer proves it over transcript plus `sharer`. No setup or identity leaves before both proofs. Failed connections have bounded reads and create no pad.
4. HKDF-SHA256 extracts with transcript as salt and NFC secret as input key material. Independently labelled `Field NFC radio v1 / A to B` and `B to A` expansions create AES-256-GCM keys. Each direction has an independent strictly increasing 64-bit sequence, a 96-bit nonce (four zero bytes plus sequence), and transcript/direction/sequence AAD. Maximum plaintext record is 32 KiB. Invalid tags, lengths, counters or reflection poison the session. Fresh nonces give fresh keys when retrying an offer before successful use. Original USB cryptography and its independent vector remain unchanged.
5. The existing v4 physical-pairing protocol runs INSIDE this envelope: its P-256 ECDH/AES-GCM layer, signed approvals, full displayed transcript code, pad/identity binding, durable anti-reuse ledger, and explicit commits. No plaintext or unauthenticated-IP fallback.

A Wi-Fi interceptor without the NFC secret sees endpoints, timing, lengths and ciphertext and can disrupt the connection. **NFC proximity does not guarantee secrecy or identity:** NFC eavesdropping/relay attacks can steal the temporary ticket or redirect trust. Full on-screen code comparison remains mandatory. Compromised endpoints remain compromised. This composed, unaudited protocol is not claimed information-theoretic, formally verified, or post-quantum secure. Wireless provisioning makes pad confidentiality depend on transfer encryption. USB remains preferred for a controlled physical exchange.

## Verification and limits

Tests cover an independent Python AES-GCM/HKDF record vector; strict ticket/address bounds; wrong secret; modified tag, counter, context and length; replay/reflection; a 4 MiB stream; and two-station pairing, cancellation and renewal over local TCP with Keystore identity and encrypted vaults. Emulator testing cannot establish actual NFC antenna/HCE interoperability, cross-device Wi-Fi routing, or Mudita Kompakt compatibility. Physical testing is required. Revisit Android local-network permissions when raising target SDK beyond 35.

References: [Android HCE](https://developer.android.com/develop/connectivity/nfc/hce), [local-network protections](https://developer.android.com/privacy-and-security/local-network-permission), [NIST NFC relay threats](https://pages.nist.gov/mobile-threat-catalogue/lan-pan-threats/LPN-12.html), [HKDF](https://www.rfc-editor.org/rfc/rfc5869), [NIST GCM](https://csrc.nist.gov/pubs/sp/800/38/d/final).

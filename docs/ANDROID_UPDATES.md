# Permanent signing and safe APK updates

The stable release path keeps package `org.example.otppager`, Android Keystore aliases,
chat storage and pad ledger locations unchanged. Android installs a higher-version APK
with the same signer over the app and preserves its data/UID. No pad export, vault
backup/restore, uninstall or re-pairing is part of a normal update.

## One-time owner setup (Windows)

Merge the signing PR and its prerequisite pairing/UI PR first. On your own Windows PC,
install Python 3, a Java 17 JDK (provides `keytool`) and GitHub CLI (`gh`) if missing.
For example, Windows Package Manager provides:

```
winget install --id Python.Python.3.12 -e
winget install --id EclipseAdoptium.Temurin.17.JDK -e
winget install --id GitHub.cli -e
```

Open a new terminal after installation. Run `gh auth login`, download/clone the
repository's current main branch and run this from its folder in PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-signing.ps1
```

This runs the checked-in setup script; inspect it before running. Linux/macOS owners
with Python 3, Java `keytool` and authenticated `gh` can use
`python3 scripts/android_signing.py setup`.

The script generates a permanent RSA-3072 PKCS12 signing key locally, creates a random
password, and restricts the private folder to the current user. Default Windows path:
`%LOCALAPPDATA%\OTPPager\signing`. It uploads the key and password directly to GitHub
Actions repository secrets through stdin, sets the **public** certificate SHA-256 pin
as a repository variable, then requests the main-branch Signed Android release workflow.
There are two secrets (`ANDROID_SIGNING_KEYSTORE`, `ANDROID_SIGNING_PASSWORD`) and one
public variable (`ANDROID_SIGNING_CERT_SHA256`). No private key or password is committed,
printed, included in APKs, attached to an artifact or sent to this chat.

**Keep a secure offline backup of the entire private folder**, including `release.p12`,
`password.txt` and the public fingerprint. Do this before relying on the signer. Losing
the key or password prevents compatible updates. The script is resumable with this same
folder and refuses to replace an existing remote signer with a different local key.
Do not delete the folder to fix an error. A partial upload can be retried with the original
folder. GitHub repo administrators and Actions runners with access to the secrets are
trusted signing infrastructure; offline/manual signing would be a separate custody model.

This connector cannot configure repository secrets, so this owner-local step is required.
It is a tooling permission limit, not an app permission prompt or an additional pairing step.

## The initial debug-to-release transition

Our existing 0.7 field APK and ordinary CI debug APKs were signed with temporary debug
keys. Their private signing keys were not retained. A new permanent key cannot update
those APKs in place. Do not promise that the old debug pairing will survive this first
transition, and do not copy/restore an operational pad to work around Android's signature
check. Keep using the old app until you are ready to establish **fresh pairings on both
devices** with the first permanently signed release. Only this initial transition may
require uninstalling the old debug app. Future same-signer updates preserve state.

## Signed builds and publishing

Only a manually requested **Signed Android release** workflow on `main` sees owner secrets.
PR validation uses disposable keys, including on same-repository PRs. The signing workflow
uses pinned action commits, disables Gradle cache restoration, checks the pinned certificate
before signing and verifies the APK afterward. It fails closed when configuration is absent
or mismatched. The private keystore is a temporary file removed on exit; no debug fallback.
The result is `otp-pager-signed-release`, containing the non-debuggable APK, `signing.json`
(source commit, package, versionCode, signer and APK SHA-256) and `SHA256SUMS`.

The existing field-release publisher accepts the verified signed artifact by adding
`signed_android_run` and `signing_cert_sha256` to `release/manifest.json` and selecting
artifact `otp-pager-signed-release`. The normal Android matrix and relay run must also
have passed at that exact source commit. Use `manifest.signed.example.json` as a template;
its placeholders deliberately cannot publish. The first signed release must explicitly
set `initial_signed_release: true`. Later releases must instead specify
`previous_signed_tag`, for example `v0.8.0`; the publisher verifies both APK signatures,
the pinned certificate, package, non-debuggable status and strictly increasing versionCode.
A second initial-release bypass is rejected. Published 0.7 debug bytes remain reproducible;
new debug-signed releases are rejected. This PR does not repoint or replace 0.7.

For each later update, increase the source versionCode and versionName, pass CI, request
its signed build, then publish those exact verified bytes. Download the stable APK and
open it using Android's normal package installer; choose Update. No app-store dependency,
Google services or new in-app package installation permission is required. Automatic
background download/install is outside this change.

To check two APKs locally with Android build-tools 35.0.0 installed:

```
python3 scripts/android_signing.py check-update old.apk new.apk --certificate YOUR_PUBLIC_SHA256_PIN
adb install -r new.apk
```

Never use `adb uninstall`, clear app data, `-d` or a vault restore as an update shortcut.
If Android reports a signature mismatch or downgrade, stop and check the APK; retain the
installed app and its state.

## What the upgrade test proves

CI builds two non-debuggable release APKs with successive test-only versionCodes and the
same disposable key. It installs the first, creates two chat profiles, a verified/pinned
identity, queued signed ciphertext, nonzero replay state and reserved pad slots, then uses
`adb install -r` for the second. The fixture verifies vault/index/claims/ledger hashes,
active chat, peer pin, the old private key's ability to sign, exact queued message state
and a new send above the previous pad high-water mark. Android must also reject an older
APK and a differently signed APK while retaining the fixture. Test versionCodes and key
material never enter production artifacts. Both test APKs use current source; this proves
Android replacement/state retention, not migration from an unavailable historical signer
or arbitrary future storage formats. Existing migration/fault tests remain required.

References:
- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/tools/apksigner
- https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target
- https://cli.github.com/manual/gh_secret_set

# Third-party components

Android 0.6 adds JourneyApps ZXing Android Embedded 4.3.0, licensed under the
Apache License, Version 2.0:
https://github.com/journeyapps/zxing-android-embedded
https://github.com/journeyapps/zxing-android-embedded/blob/master/COPYING

It includes ZXing core under the Apache License, Version 2.0:
https://github.com/zxing/zxing
https://github.com/zxing/zxing/blob/master/LICENSE

Transitive AndroidX components are under the Apache License, Version 2.0:
https://source.android.com/docs/setup/about/licenses

These libraries are used for embedded offline barcode scanning/encoding. They keep
their own licenses; this project's own source code is under the GNU GPL v3.0 only (GPL-3.0-only; see LICENSE).

Android 0.17 adds Bouncy Castle `bcprov-jdk18on` 1.86 for ML-KEM-768 (FIPS 203) in the hybrid
post-quantum USB pairing channel, under the Bouncy Castle License (MIT-style):
https://www.bouncycastle.org/about/license/
Only its lightweight cryptography API is used; no JCA provider is registered.

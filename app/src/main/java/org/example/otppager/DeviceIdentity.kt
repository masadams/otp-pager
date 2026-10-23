package org.example.otppager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Never silently replaces a missing identity after its public key has been saved. */
class DeviceIdentity(private val alias: String) {
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun card(create: Boolean = false): String {
        if (!keys().containsAlias(alias)) {
            check(create) { "Device identity key missing; stop and arrange a fresh physical pairing" }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        return Base64.getEncoder().encodeToString(keys().getCertificate(alias).publicKey.encoded)
    }
    fun check(expected: String) { check(card() == expected) { "Device identity changed; fresh physical pairing required" } }
    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign((keys().getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey); update(data); sign()
    }
    @Suppress("DEPRECATION")
    fun protection(): String {
        val key = (keys().getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        return if (info.isInsideSecureHardware) "HARDWARE KEYSTORE" else "SOFTWARE KEYSTORE"
    }
    fun forget() { keys().deleteEntry(alias) }
}

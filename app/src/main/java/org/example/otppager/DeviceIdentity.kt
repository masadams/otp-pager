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
class DeviceIdentity(private val alias: String, private val requireActive: () -> Unit) {
    // Keep the original one-argument ABI used by signed upgrade instrumentation on older APKs.
    constructor(alias: String) : this(alias, {})
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun card(create: Boolean = false): String = synchronized(VaultLifecycle.lock) { requireActive(); cardActive(create) }
    private fun cardActive(create: Boolean): String {
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
    fun sign(data: ByteArray): ByteArray = synchronized(VaultLifecycle.lock) {
        requireActive(); signActive(data)
    }
    private fun signActive(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign((keys().getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey); update(data); sign()
    }
    @Suppress("DEPRECATION")
    fun protection(): String = synchronized(VaultLifecycle.lock) { requireActive(); protectionActive() }
    @Suppress("DEPRECATION")
    private fun protectionActive(): String {
        val key = (keys().getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        return if (info.isInsideSecureHardware) "HARDWARE KEYSTORE" else "SOFTWARE KEYSTORE"
    }
    fun forget() = synchronized(VaultLifecycle.lock) { keys().deleteEntry(alias) }
}

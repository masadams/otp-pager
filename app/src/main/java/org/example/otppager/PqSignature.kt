package org.example.otppager

import org.bouncycastle.crypto.params.MLDSAParameters
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters
import org.bouncycastle.crypto.signers.MLDSASigner
import java.security.SecureRandom

/**
 * ML-DSA-65 (FIPS 204), pure mode with an empty context, as Node/OpenSSL 3.5 `crypto.sign(null, …)`
 * produces. Used next to ECDSA (both must verify) for strict-chat grants and unlocks
 * (docs/POST_QUANTUM.md). Keys travel as X.509 SubjectPublicKeyInfo; private keys are 32-byte seeds.
 */
internal object PqSignature {
    const val PUBLIC_BYTES = 1952
    const val SIGNATURE_BYTES = 3309
    const val SEED_BYTES = 32
    /** DER SubjectPublicKeyInfo header for id-ml-dsa-65 (2.16.840.1.101.3.4.3.18). */
    private val SPKI_PREFIX = PadProtocol.hexBytes("308207b2300b0609608648016503040312038207a100")
    const val SPKI_BYTES = 22 + PUBLIC_BYTES

    fun newSeed(): ByteArray = ByteArray(SEED_BYTES).also(SecureRandom()::nextBytes)
    private fun privateKey(seed: ByteArray): MLDSAPrivateKeyParameters {
        require(seed.size == SEED_BYTES)
        return MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, seed)
    }
    fun publicKey(seed: ByteArray): ByteArray = SPKI_PREFIX + privateKey(seed).publicKeyParameters.encoded
    private fun parse(spki: ByteArray): MLDSAPublicKeyParameters {
        require(spki.size == SPKI_BYTES && spki.copyOf(SPKI_PREFIX.size).contentEquals(SPKI_PREFIX)) { "Not an ML-DSA-65 key" }
        return MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, spki.copyOfRange(SPKI_PREFIX.size, spki.size))
    }
    /** Checks the encoding only (as a pin is stored). */
    fun validKey(spki: ByteArray): Boolean = runCatching { parse(spki) }.isSuccess
    fun sign(seed: ByteArray, data: ByteArray): ByteArray = MLDSASigner().run {
        init(true, privateKey(seed)); update(data, 0, data.size); generateSignature()
    }
    fun verify(spki: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false
        val key = try { parse(spki) } catch (_: IllegalArgumentException) { return false }
        return MLDSASigner().run { init(false, key); update(data, 0, data.size); verifySignature(signature) }
    }
}

package org.example.otppager

import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import java.security.SecureRandom

/**
 * ML-KEM-768 (FIPS 203) for the physical pairing channel. Always used together with ECDH
 * (see [CableSession.establish]), so the channel stays secure if either one holds: a future
 * quantum computer breaking ECDH, or a flaw found in ML-KEM. Keys are ephemeral, one per
 * pairing session, and never stored. Uses Bouncy Castle's lightweight API directly, so no
 * JCA provider is registered and Android's own provider is untouched.
 */
internal object PostQuantum {
    const val KEM_PUBLIC_BYTES = 1184
    const val KEM_CIPHERTEXT_BYTES = 1088
    const val KEM_SECRET_BYTES = 32
    private val parameters = MLKEMParameters.ml_kem_768

    class KemKeyPair internal constructor(val public: ByteArray, internal val private: MLKEMPrivateKeyParameters)
    class Encapsulated(val ciphertext: ByteArray, val secret: ByteArray)

    fun kemKeyPair(random: SecureRandom = SecureRandom()): KemKeyPair {
        val pair = MLKEMKeyPairGenerator().apply { init(MLKEMKeyGenerationParameters(random, parameters)) }.generateKeyPair()
        return KemKeyPair((pair.public as MLKEMPublicKeyParameters).encoded, pair.private as MLKEMPrivateKeyParameters)
    }
    /** Rejects anything but a well-formed ML-KEM-768 encapsulation key. */
    fun checkPublic(key: ByteArray) {
        require(key.size == KEM_PUBLIC_BYTES) { "Invalid post-quantum pairing key" }
        MLKEMPublicKeyParameters(parameters, key)
    }
    fun encapsulate(peerPublic: ByteArray, random: SecureRandom = SecureRandom()): Encapsulated {
        checkPublic(peerPublic)
        val result = MLKEMGenerator(random).generateEncapsulated(MLKEMPublicKeyParameters(parameters, peerPublic))
        try { return Encapsulated(result.encapsulation, result.secret.copyOf()) } finally { result.destroy() }
    }
    fun decapsulate(own: KemKeyPair, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == KEM_CIPHERTEXT_BYTES) { "Invalid post-quantum pairing ciphertext" }
        return MLKEMExtractor(own.private).extractSecret(ciphertext).also { check(it.size == KEM_SECRET_BYTES) }
    }
}

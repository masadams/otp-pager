package org.example.otppager

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.AlgorithmParameters
import java.util.Base64

/** Explicit signed-message profile, not a deniable session or an audited messaging protocol. */
object IdentityProtocol {
    private const val PREFIX = "FD1:"
    fun publicKey(card: String): PublicKey {
        require(card.length in 100..160) { "Paste the other station's public identity card" }
        val bytes = Base64.getDecoder().decode(card)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        require(key.params.order == expected.order && key.params.curve == expected.curve && key.params.generator == expected.generator && key.params.cofactor == expected.cofactor)
        require(Base64.getEncoder().encodeToString(key.encoded) == card) { "Noncanonical public identity card" }
        return key
    }
    fun fingerprint(card: String): String = PadProtocol.hex(MessageDigest.getInstance("SHA-256").digest(publicKey(card).encoded))
    fun transcript(room: String, pad: String, role: Char, counter: Long, sender: String, recipient: String, body: String): ByteArray {
        require(Regex("[a-f0-9]{64}").matches(room) && Regex("[a-f0-9]{32}").matches(pad))
        require(role in listOf('A', 'B') && counter > 0)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun part(value: ByteArray) { out.writeInt(value.size); out.write(value) }
            part("Field device signature v1 / SHA256withECDSA / P-256".toByteArray(Charsets.US_ASCII))
            part(room.toByteArray(Charsets.US_ASCII)); part(pad.toByteArray(Charsets.US_ASCII))
            out.writeByte(role.code); out.writeLong(counter)
            part(publicKey(sender).encoded); part(publicKey(recipient).encoded); part(body.toByteArray(Charsets.UTF_8))
        }
        return bytes.toByteArray()
    }
    fun signed(body: String, sign: (String) -> ByteArray): String = PREFIX + Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8)) + ":" + Base64.getEncoder().encodeToString(sign(body))
    fun isSigned(value: String) = value.startsWith(PREFIX)
    fun body(value: String, verify: (String, ByteArray) -> Boolean): String {
        require(value.length <= PadProtocol.BLOCK - 2 && isSigned(value)) { "Device signature required" }
        val parts = value.removePrefix(PREFIX).split(':')
        require(parts.size == 2)
        val bytes = Base64.getDecoder().decode(parts[0]); val signature = Base64.getDecoder().decode(parts[1])
        require(signature.size in 8..80)
        val body = String(bytes, Charsets.UTF_8)
        require(body.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        require(verify(body, signature)) { "Device signature rejected" }
        return body
    }
    fun verify(card: String, data: ByteArray, signature: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(publicKey(card)); update(data); verify(signature)
    }
}

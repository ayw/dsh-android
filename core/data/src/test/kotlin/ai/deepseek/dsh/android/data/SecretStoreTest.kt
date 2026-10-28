package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.model.ConnectionIdentity
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * G09.4：凭据信封的纯 JVM 验证。密钥是固定 [SecretKeySpec]（不碰 AndroidKeyStore），
 * 因此这里断言的是算法/布局/AAD 本身，而不是某台设备的 provider 行为。
 */
class SecretStoreTest {

    private val identity = ConnectionIdentity("a".repeat(64))
    private val otherIdentity = ConnectionIdentity("b".repeat(64))
    private val token = "canary-token-4f8d2c"

    private fun store(): SecretStore {
        val key = SecretKeySpec(ByteArray(32) { index -> index.toByte() }, "AES")
        return SecretStore { key }
    }

    @Test
    fun roundTripReturnsOriginalToken() {
        val store = store()
        val blob = store.encrypt(identity, token)
        assertEquals(token, store.decrypt(identity, blob))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val store = store()
        val first = store.encrypt(identity, token)
        val second = store.encrypt(identity, token)
        assertFalse(SecretStore.ivOf(first).contentEquals(SecretStore.ivOf(second)))
        assertEquals(SecretStore.IV_BYTES, SecretStore.ivOf(first).size)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun sameTokenUnderDifferentIdentityProducesDifferentCiphertext() {
        val store = store()
        val a = store.encrypt(identity, token)
        val b = store.encrypt(otherIdentity, token)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun wrongIdentityFailsAuthentication() {
        val store = store()
        val blob = store.encrypt(identity, token)
        val failure = assertFailsWith<SecretStoreException.AuthenticationFailed> {
            store.decrypt(otherIdentity, blob)
        }
        assertNoTokenIn(failure)
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val store = store()
        val blob = store.encrypt(identity, token)
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 0x01).toByte()
        val failure = assertFailsWith<SecretStoreException.AuthenticationFailed> {
            store.decrypt(identity, blob)
        }
        assertNoTokenIn(failure)
    }

    @Test
    fun tamperedIvFailsAuthentication() {
        val store = store()
        val blob = store.encrypt(identity, token)
        blob[SecretStore.HEADER_BYTES] = (blob[SecretStore.HEADER_BYTES].toInt() xor 0x01).toByte()
        assertFailsWith<SecretStoreException.AuthenticationFailed> { store.decrypt(identity, blob) }
    }

    @Test
    fun wrongMagicIsRejectedBeforeDecryption() {
        val store = store()
        val blob = store.encrypt(identity, token)
        blob[0] = 0x00.toByte()
        val failure = assertFailsWith<SecretStoreException.UnsupportedMagic> { store.decrypt(identity, blob) }
        assertNoTokenIn(failure)
    }

    @Test
    fun wrongVersionIsRejectedBeforeDecryption() {
        val store = store()
        val blob = store.encrypt(identity, token)
        blob[SecretStore.HEADER_BYTES - 1] = 9.toByte()
        val failure = assertFailsWith<SecretStoreException.UnsupportedVersion> { store.decrypt(identity, blob) }
        assertNoTokenIn(failure)
    }

    @Test
    fun truncatedBlobIsRejected() {
        val store = store()
        val blob = store.encrypt(identity, token)
        assertFailsWith<SecretStoreException.Truncated> {
            store.decrypt(identity, blob.copyOf(SecretStore.HEADER_BYTES + SecretStore.IV_BYTES))
        }
    }

    @Test
    fun tokenNeverAppearsInBlobOrExceptionText() {
        val store = store()
        val blob = store.encrypt(identity, token)
        // 密文里不应出现明文（这是加密，不是编码）。
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(token))
        // 所有结构化失败都不得把 token 带进 message/toString：它们可能进入日志或崩溃报告。
        val failures = listOf(
            runCatching { store.decrypt(otherIdentity, blob) }.exceptionOrNull(),
            runCatching { store.decrypt(identity, blob.copyOf(blob.size - 1)) }.exceptionOrNull(),
        )
        failures.filterNotNull().forEach { assertNoTokenIn(it) }
        // 失败类型是公开契约（结构化、可分支），类型名本身不含任何秘密。
        assertEquals(
            listOf("Truncated", "UnsupportedMagic", "UnsupportedVersion", "AuthenticationFailed"),
            listOf(
                SecretStoreException.Truncated,
                SecretStoreException.UnsupportedMagic,
                SecretStoreException.UnsupportedVersion,
                SecretStoreException.AuthenticationFailed,
            ).map { it.javaClass.simpleName },
        )
    }

    private fun assertNoTokenIn(failure: Throwable) {
        assertFalse(failure.message.orEmpty().contains(token))
        assertFalse(failure.toString().contains(token))
        assertTrue(failure is SecretStoreException)
    }
}

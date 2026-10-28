package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.model.ConnectionIdentity
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据密钥的来源。抽成接口的唯一理由是测试：JVM 单测不能碰 AndroidKeyStore，
 * 但加解密逻辑必须能在 JVM 上逐字节验证（G09.4）。
 */
fun interface AesGcmKeyProvider {
    fun key(): SecretKey
}

/**
 * 凭据 blob 的结构化失败。消息里只有结构信息，**永远不含 token 或 identity 明文**：
 * 这类异常会进日志/崩溃报告，任何原文都会变成一次凭据泄露（D14/D16）。
 */
sealed class SecretStoreException(message: String) : Exception(message) {
    data object Truncated : SecretStoreException("credential blob truncated")
    data object UnsupportedMagic : SecretStoreException("credential blob magic mismatch")
    data object UnsupportedVersion : SecretStoreException("credential blob version unsupported")
    data object AuthenticationFailed : SecretStoreException("credential blob authentication failed")
}

/**
 * 凭据信封（D14）：AES-256-GCM、随机 12 字节 IV、AAD = `identity.digest` 的 UTF-8 字节。
 *
 * blob 布局 `magic(4) | version(1) | iv(12) | ciphertext+tag`：
 *  - magic/version 让“这不是我们的 blob”与“这是旧版本 blob”在解密前就被拒绝，而不是伪装成 GCM 认证失败；
 *  - IV 随 blob 一起走，数据库只做搬运，不必知道偏移；AAD 绑定 digest 意味着把 A 的密文挪给 B 用
 *    （换主机/换 token 的混配）会得到认证失败而不是错误身份下的可用凭据；
 *  - 未注册的 magic/版本一律拒绝，不做兼容猜测：凭据格式变化应当是一次显式的版本升级。
 */
class SecretStore(private val keys: AesGcmKeyProvider) {

    private val random = SecureRandom()

    /** 加密明文 token。同一 token 两次加密必然得到不同 blob（IV 随机）。 */
    fun encrypt(identity: ConnectionIdentity, token: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(identity.digest.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return ByteArray(HEADER_BYTES + IV_BYTES + sealed.size).also { blob ->
            MAGIC.copyInto(blob, 0)
            blob[HEADER_BYTES - 1] = VERSION
            iv.copyInto(blob, HEADER_BYTES)
            sealed.copyInto(blob, HEADER_BYTES + IV_BYTES)
        }
    }

    /**
     * 解密。任何结构/认证问题都抛 [SecretStoreException]，绝不返回部分明文；
     * AAD 不匹配（identity 换过）与密文被篡改在 GCM 里同属认证失败，不做区分——区分只会给攻击者信息。
     */
    fun decrypt(identity: ConnectionIdentity, blob: ByteArray): String {
        if (blob.size < HEADER_BYTES + IV_BYTES + TAG_BYTES) throw SecretStoreException.Truncated
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) throw SecretStoreException.UnsupportedMagic
        }
        if (blob[HEADER_BYTES - 1] != VERSION) throw SecretStoreException.UnsupportedVersion

        val iv = blob.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(identity.digest.toByteArray(Charsets.UTF_8))
        val plain = try {
            cipher.doFinal(blob, HEADER_BYTES + IV_BYTES, blob.size - HEADER_BYTES - IV_BYTES)
        } catch (badTag: AEADBadTagException) {
            throw SecretStoreException.AuthenticationFailed
        } catch (other: GeneralSecurityException) {
            // 例如 provider 拒绝参数。同样归为认证失败：调用方只需要“不可用”，不需要密码学细节。
            throw SecretStoreException.AuthenticationFailed
        }
        return String(plain, Charsets.UTF_8)
    }

    companion object {
        /** GCM 推荐的 IV 长度；随机生成，绝不复用（复用会直接毁掉 GCM 的语义安全）。 */
        const val IV_BYTES = 12

        /** GCM 认证标签位数。 */
        const val TAG_BITS = 128

        /** magic(4) + version(1)。 */
        const val HEADER_BYTES = 5

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BYTES = TAG_BITS / 8
        private const val VERSION: Byte = 1

        /** "DSHC"。用十六进制书写，避免版本号/魔数被误读成尺寸常量。 */
        private val MAGIC = byteArrayOf(0x44, 0x53, 0x48, 0x43)

        /**
         * 从 blob 取出 IV，供 `server_credential.iv` 列单独保存（D14 的诊断/迁移用）。
         * 解密仍以 blob 内嵌 IV 为准，这一列不是真相来源。
         */
        fun ivOf(blob: ByteArray): ByteArray {
            if (blob.size < HEADER_BYTES + IV_BYTES) throw SecretStoreException.Truncated
            return blob.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)
        }
    }
}

/**
 * 生产密钥来源：AndroidKeyStore 里不可导出的 AES-256 密钥（D14）。
 *
 * StrongBox 只在 API 28+ 尝试；不可用（设备没有安全芯片、密钥槽满、厂商实现拒绝）时回退普通
 * AndroidKeyStore 密钥并记下 [strongBoxFallbackUsed]，**不抛**：安全等级降级是可诊断的状态，
 * 而不是让配对失败。密钥一旦生成就不可再“升级”到 StrongBox，所以两个标志只在首次生成/加载时刷新。
 */
class AndroidKeystoreKeyProvider(
    private val alias: String = KEY_ALIAS,
) : AesGcmKeyProvider {

    /** 当前密钥确实位于安全硬件内。 */
    @Volatile
    var strongBoxBacked: Boolean = false
        private set

    /** 曾尝试 StrongBox 但回退到普通密钥；这是 D14 要求记录的诊断事实。 */
    @Volatile
    var strongBoxFallbackUsed: Boolean = false
        private set

    private val lock = Any()

    override fun key(): SecretKey = synchronized(lock) { loadOrCreate() }

    private fun loadOrCreate(): SecretKey {
        loadExisting()?.let { existing ->
            strongBoxBacked = insideSecureHardware(existing)
            return existing
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val strong = generate(strongBox = true)
                strongBoxBacked = true
                return strong
            } catch (failure: Exception) {
                // StrongBox 不可用时某些实现仍可能留下别名；先确认，避免下面生成时撞名。
                strongBoxBacked = false
                strongBoxFallbackUsed = true
                loadExisting()?.let { return it }
            }
        }
        val fallback = generate(strongBox = false)
        strongBoxBacked = false
        return fallback
    }

    private fun loadExisting(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        keyStore.getKey(alias, null) as? SecretKey
    }.getOrNull()

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_TYPE)
            .apply { init(builder.build()) }
            .generateKey()
    }

    private fun insideSecureHardware(key: SecretKey): Boolean = runCatching {
        KeyFactory.getInstance(key.algorithm, KEYSTORE_TYPE)
            .getKeySpec(key, KeyInfo::class.java)
            .isInsideSecureHardware
    }.getOrDefault(false)

    companion object {
        /** D14 冻结的别名；改名等于让所有已配对设备丢掉凭据。 */
        const val KEY_ALIAS = "dsh.credential.v1"

        private const val KEYSTORE_TYPE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}

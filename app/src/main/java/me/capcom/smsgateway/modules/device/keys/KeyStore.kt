package me.capcom.smsgateway.modules.device.keys

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

data class KeyPairResult(
    val keyPair: KeyPair,
    val persistedBlob: ByteArray,
    val publicKeyBase64: String,
)

data class KeyLoadResult(
    val privateKey: PrivateKey,
)

class KeyStore(
    private val context: Context,
) {

    private val storageCipher: StorageBlobCipher by lazy {
        StorageBlobCipher(keyMaterial())
    }

    fun generateKeyPair(alias: String): KeyPairResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val keyPair = generateKeyPairInKeystore(alias)
            KeyPairResult(
                keyPair,
                ByteArray(0), // private key lives in AndroidKeyStore
                encodePublicKey(keyPair),
            )
        } else {
            val keyPair = generateKeyPairSoftware()
            KeyPairResult(
                keyPair,
                encryptPrivateKeyForStorage(keyPair.private),
                encodePublicKey(keyPair),
            )
        }
    }

    fun getPrivateKey(alias: String, persistedBlob: ByteArray): KeyLoadResult? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getKeyFromKeystore(alias)?.let { KeyLoadResult(it) }
        } else {
            if (persistedBlob.isEmpty()) return null
            val decrypted = decryptPrivateKeyFromStorage(persistedBlob)
            val keySpec = PKCS8EncodedKeySpec(decrypted)
            val privateKey = KeyFactory.getInstance(RSA_ALGORITHM).generatePrivate(keySpec)
            KeyLoadResult(privateKey)
        }
    }

    fun delete(alias: String) {
        deleteKeyFromKeystore(alias)
    }

    private fun encodePublicKey(keyPair: KeyPair): String {
        return Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
    }

    // -------------------------------------------------------------------------
    // API 23+ : AndroidKeyStore
    // -------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.M)
    private fun generateKeyPairInKeystore(alias: String): KeyPair {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT,
        )
            .setKeySize(2048)
            .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        kpg.initialize(spec)
        return kpg.generateKeyPair()
    }

    private fun getKeyFromKeystore(alias: String): PrivateKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        return ks.getKey(alias, null) as? PrivateKey
    }

    private fun deleteKeyFromKeystore(alias: String) {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        ks.deleteEntry(alias)
    }

    // -------------------------------------------------------------------------
    // API 21-22 : Software keypair, encrypted private key in Room
    // -------------------------------------------------------------------------

    private fun generateKeyPairSoftware(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(RSA_ALGORITHM)
        kpg.initialize(2048)
        return kpg.generateKeyPair()
    }

    private fun encryptPrivateKeyForStorage(privateKey: PrivateKey): ByteArray {
        return storageCipher.encrypt(privateKey.encoded)
    }

    private fun decryptPrivateKeyFromStorage(encryptedBlob: ByteArray): ByteArray {
        return storageCipher.decrypt(encryptedBlob)
    }

    /**
     * Material for wrapping the software (API 21-22) private-key blob.
     *
     * KNOWN LIMITATION: AndroidKeyStore is only available from API 23
     * (minSdk is 21), so on API 21-22 the private key is wrapped with a
     * secret derived from ANDROID_ID and the package name, which are
     * predictable to an attacker who copies the database and the
     * installation metadata. This provides obfuscation rather than strong
     * secrecy and is a deliberate tradeoff for supporting Android 5.x
     * devices. API 23+ never uses this path.
     */
    @SuppressLint("HardwareIds")
    private fun keyMaterial(): String {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "fallback"
        return "$androidId:${context.packageName}"
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val RSA_ALGORITHM = "RSA"
    }
}

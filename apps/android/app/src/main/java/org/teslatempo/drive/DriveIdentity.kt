package org.teslatempo.drive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

class DriveIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("drive_identity", Context.MODE_PRIVATE)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun keyPair(): KeyPair = synchronized(lock) {
        val ks = store()
        if (ks.containsAlias(KEY)) KeyPair(ks.getCertificate(KEY).publicKey, ks.getKey(KEY, null) as PrivateKey)
        else KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build())
        }.generateKeyPair()
    }
    private fun sealKey(): SecretKey = synchronized(lock) {
        (store().getKey(SEAL, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(SEAL, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun saveVin(raw: String) {
        val vin = normalizedVin(raw)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, sealKey()) }
        check(prefs.edit().putString("vin", Base64.encodeToString(cipher.iv + cipher.doFinal(vin.toByteArray()), Base64.NO_WRAP)).commit())
    }
    fun loadVin(): String? {
        val text = prefs.getString("vin", null) ?: return null
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, sealKey(), GCMParameterSpec(128, bytes.copyOfRange(0,12))) }
        return normalizedVin(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size))))
    }
    // Removing locally does NOT remove an enrolled key from the vehicle.
    fun forgetVin() { check(prefs.edit().remove("vin").commit()) }
    companion object {
        private val lock = Any()
        private const val KEY = "teslatempo.drive.monitor.v1"
        private const val SEAL = "teslatempo.drive.vin.v1"
        fun publicBytes(key: PublicKey): ByteArray {
            val ec = key as ECPublicKey
            fun scalar(n: java.math.BigInteger) = n.toByteArray().let { ByteArray(32 - minOf(32,it.size)) + it.takeLast(32).toByteArray() }
            return byteArrayOf(4) + scalar(ec.w.affineX) + scalar(ec.w.affineY)
        }
    }
}

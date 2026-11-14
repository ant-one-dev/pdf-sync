package com.orbisrobotics.pdfsync

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Réseau Wi-Fi Direct commun à toutes les tablettes. Nom et mot de passe sont fixés
 * à l'avance (Android 10+) pour que les Clients se connectent sans popup de confirmation.
 */
object NetworkSettings {
    /** Dans un groupe Wi-Fi Direct, le propriétaire du groupe est toujours en .49.1 */
    const val HOST_IP = "192.168.49.1"
    const val MIN_PASS = 12
    private const val PREFS = "network"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Android impose le préfixe DIRECT-xy-
    fun ssid(ctx: Context) = "DIRECT-OR-" + room(ctx)

    fun room(ctx: Context): String = prefs(ctx).getString("room", "") ?: ""

    fun passphrase(ctx: Context): String =
        prefs(ctx).getString("pass", null)?.let { SecretStore.decrypt(it) } ?: ""

    fun isConfigured(ctx: Context) = room(ctx).isNotEmpty() && passphrase(ctx).length >= MIN_PASS

    fun save(ctx: Context, room: String, pass: String) {
        prefs(ctx).edit()
            .putString("room", room)
            .putString("pass", SecretStore.encrypt(pass))
            .apply()
    }

    fun p2pConfig(ctx: Context): WifiP2pConfig? =
        if (Build.VERSION.SDK_INT >= 29) {
            WifiP2pConfig.Builder()
                .setNetworkName(ssid(ctx))
                .setPassphrase(passphrase(ctx))
                .build()
        } else null
}

/**
 * AES-256-GCM avec une clé générée dans l'Android Keystore (non exportable,
 * dans le TEE / StrongBox quand la tablette en a un).
 */
object SecretStore {
    private const val ALIAS = "pdfsync_network"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray())
        return b64(c.iv) + ":" + b64(ct)
    }

    fun decrypt(stored: String): String? = try {
        val (iv, ct) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct))
    } catch (e: Exception) {
        null
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}

/**
 * Code administrateur (protège les réglages réseau). On ne stocke que PBKDF2(code, sel).
 * 5 erreurs => blocage 5 min, qui double à chaque nouvelle série.
 */
object AdminLock {
    private const val PREFS = "admin"
    private const val ITERATIONS = 20_000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(ctx: Context) = prefs(ctx).contains("hash")

    private fun hash(code: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256)).encoded

    fun set(ctx: Context, code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(code, salt), Base64.NO_WRAP))
            .putInt("fails", 0).putInt("rounds", 0).putLong("until", 0)
            .apply()
    }

    fun lockedSeconds(ctx: Context): Long =
        ((prefs(ctx).getLong("until", 0) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    fun check(ctx: Context, code: String): Boolean {
        if (lockedSeconds(ctx) > 0) return false
        val p = prefs(ctx)
        val salt = Base64.decode(p.getString("salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(p.getString("hash", ""), Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(hash(code, salt), expected)
        if (ok) {
            p.edit().putInt("fails", 0).putInt("rounds", 0).apply()
            return true
        }
        val fails = p.getInt("fails", 0) + 1
        if (fails < 5) {
            p.edit().putInt("fails", fails).apply()
        } else {
            val rounds = p.getInt("rounds", 0)
            val minutes = 5L shl rounds.coerceAtMost(6)
            p.edit().putInt("fails", 0).putInt("rounds", rounds + 1)
                .putLong("until", System.currentTimeMillis() + minutes * 60_000).apply()
        }
        return false
    }
}

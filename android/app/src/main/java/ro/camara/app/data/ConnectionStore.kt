package ro.camara.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.InetAddress
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import ro.camara.app.BuildConfig

data class ConnectionConfig(
    val baseUrl: String,
    val token: String,
    internal val storageUrl: String = baseUrl,
    val lanAddress: String? = null,
) {
    internal val storageIdentity: String get() = "$storageUrl\n$token"

    fun validated(allowHttp: Boolean = BuildConfig.DEBUG): ConnectionConfig {
        val clean = baseUrl.trim().trimEnd('/')
        val uri = try { URI(clean) } catch (_: Exception) { error("Adresa serverului nu este validă.") }
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Introdu adresa serverului." }
        require(uri.scheme == "https" || allowHttp) { "Folosește adresa HTTPS a serverului." }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty().isEmpty()) { "Introdu doar adresa serverului, fără cale sau parolă." }
        val host = uri.host.removePrefix("[").removeSuffix("]")
        require(uri.port == -1 || uri.port in 1..65535) { "Portul serverului nu este valid." }
        val loopback = host == "localhost" || host == "::1" || ipv4(host)?.first() == 127
        require(uri.scheme == "https" || privateIp(host) || loopback) { "Pentru HTTP folosește IP-ul privat al serverului. Pentru alte adrese este necesar HTTPS." }
        val lan = lanAddress?.trim()?.removeSurrounding("[", "]")?.ifBlank { null }
        require(lan == null || (uri.scheme == "https" && privateIp(lan))) {
            "Ruta LAN cere o adresă HTTPS a serverului și un IP privat."
        }
        val key = token.trim()
        require(key.length >= 32) { "Cheia de acces trebuie să aibă cel puțin 32 de caractere." }
        require(key.all { it == '\t' || it in ' '..'~' }) { "Cheia conține caractere care nu pot fi trimise în antetul HTTP." }
        return copy(baseUrl = clean, token = key, storageUrl = if (storageUrl == baseUrl) clean else storageUrl, lanAddress = lan)
    }

    companion object {
        private fun ipv4(host: String): List<Int>? {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } ||
                    (it.length > 1 && it.startsWith('0')) }) return null
            return parts.map { it.toInt() }.takeIf { octets -> octets.all { it in 0..255 } }
        }

        private fun privateIp(host: String): Boolean {
            val address = ipv4(host)
            if (address != null) return address[0] == 10 || (address[0] == 192 && address[1] == 168) ||
                (address[0] == 172 && address[1] in 16..31)
            // Only numeric ULA IPv6 literals reach getByName; no DNS lookup is needed.
            return host.contains(':') && (host.startsWith("fc", true) || host.startsWith("fd", true)) &&
                host.all { it in "0123456789abcdefABCDEF:" } &&
                runCatching { InetAddress.getByName(host).address.size == 16 }.getOrDefault(false)
        }
    }
}

class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val alias = "camara.connection.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): ConnectionConfig? = runCatching {
        val url = prefs.getString("url", null) ?: return null
        val encrypted = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val lan = prefs.getString("lan_ip", "").orEmpty().ifBlank { null }
        ConnectionConfig(url, String(cipher.doFinal(encrypted), Charsets.UTF_8),
            prefs.getString("storage_url", url) ?: url, lan).validated(allowHttp = true)
    }.getOrNull()

    fun save(config: ConnectionConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(config.token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("url", config.baseUrl)
            .putString("storage_url", config.storageUrl)
            .putString("lan_ip", config.lanAddress.orEmpty())
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "Nu pot salva conexiunea." }
    }
}

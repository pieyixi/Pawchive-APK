package pw.pawchive.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class StoredSession(val username: String, val cookies: List<String>)

/** No credentials in preferences or Android backup; decryption failure returns logged-out. */
class SessionVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "session.aesgcm"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("pawchive.session.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pawchive.session.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(): StoredSession? = runCatching {
        val data = file.readFully()
        require(data.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        wireJson.decodeFromString<StoredSession>(cipher.doFinal(data.copyOfRange(12, data.size)).decodeToString())
    }.getOrNull()
    @Synchronized fun save(session: StoredSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(wireJson.encodeToString(session).encodeToByteArray())
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun clear() = file.delete()
}

class SiteCookieJar(initial: List<String> = emptyList(), private val changed: (List<String>) -> Unit = {}) : CookieJar {
    private val origin = okhttp3.HttpUrl.Builder().scheme("https").host("pawchive.pw").build()
    private val cookies = initial.mapNotNull { Cookie.parse(origin, it) }.toMutableList()
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        if (url.host != "pawchive.pw" || !url.isHttps) emptyList() else cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.host != "pawchive.pw" || !url.isHttps) return
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
        changed(this.cookies.map { it.toString() })
    }
    @Synchronized fun snapshot() = cookies.map { it.toString() }
}

package com.guans.widgets.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 关联 GitHub 账号：GitHub App 的设备授权登录（device flow）。
 *
 * App 里显示一个验证码，你在 GitHub 网页上确认即可，不用输入账号密码。
 * GitHub App 的权限是只读的（Actions、Contents、Metadata），令牌只能看、不能改仓库；
 * 令牌用 Android Keystore 加密后保存在本机。
 */
object GitHubAuth {
    /** GitHub App 的 client ID 和地址名（公开信息，不是密钥）。 */
    const val CLIENT_ID = "Iv23liBSCZPzYMsBZQzJ"
    const val APP_SLUG = "toolbox-widgets-muqiu"

    val configured: Boolean get() = CLIENT_ID.isNotEmpty()

    /** 在某个账号上安装 GitHub App 的页面：装上之后它的仓库（含私有）才对令牌可见。 */
    val installUrl: String get() = "https://github.com/apps/$APP_SLUG/installations/new"

    data class Account(val login: String, val token: String)

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresAt: Long,
    )

    // ---------- 设备授权

    suspend fun start(): DeviceCode = withContext(Dispatchers.IO) {
        val json = post("https://github.com/login/device/code", mapOf("client_id" to CLIENT_ID))
        DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            intervalSeconds = json.optInt("interval", 5),
            expiresAt = System.currentTimeMillis() + json.optInt("expires_in", 900) * 1000L,
        )
    }

    class AuthException(val reason: String) : Exception(reason)

    /** 轮询直到你在网页上确认；成功后保存账号并返回。拒绝或过期时抛 AuthException。 */
    suspend fun await(context: Context, code: DeviceCode): Account = withContext(Dispatchers.IO) {
        var interval = code.intervalSeconds
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(interval * 1000L)
            val json = post(
                "https://github.com/login/oauth/access_token",
                mapOf(
                    "client_id" to CLIENT_ID,
                    "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            )
            val token = json.optString("access_token")
            if (token.isNotEmpty()) {
                val login = JSONObject(GitHub.request("https://api.github.com/user", token)).getString("login")
                val account = Account(login, token)
                save(context, account)
                return@withContext account
            }
            when (val error = json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = json.optInt("interval", interval + 5)
                else -> throw AuthException(error.ifEmpty { "unknown" })
            }
        }
        throw AuthException("expired_token")
    }

    private fun post(url: String, form: Map<String, String>): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Toolbox")
        val body = form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return JSONObject(stream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 账号存储（令牌加密）

    private const val PREFS = "github_accounts"
    private const val KEY_ALIAS = "toolbox_github_tokens"

    fun accounts(context: Context): List<Account> {
        val raw = prefs(context).getString("accounts", "[]")
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            val json = array.getJSONObject(i)
            val token = runCatching { decrypt(json.getString("token")) }.getOrNull() ?: return@mapNotNull null
            Account(json.getString("login"), token)
        }
    }

    fun account(context: Context, login: String): Account? = accounts(context).firstOrNull { it.login == login }

    private fun save(context: Context, account: Account) {
        val others = accounts(context).filter { it.login != account.login }
        write(context, others + account)
    }

    fun remove(context: Context, login: String) {
        write(context, accounts(context).filter { it.login != login })
    }

    private fun write(context: Context, accounts: List<Account>) {
        val array = JSONArray(accounts.map { JSONObject().put("login", it.login).put("token", encrypt(it.token)) })
        prefs(context).edit().putString("accounts", array.toString()).apply()
    }

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return String(cipher.doFinal(data, 12, data.size - 12))
    }
}

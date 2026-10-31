package com.gym.hisab.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Non-secret UI/account state. Subscription info here is only a DISPLAY cache; the server enforces it. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("hisab_prefs", Context.MODE_PRIVATE)

    private val _gymId = MutableStateFlow(sp.getString("gym_id", null))
    private val _gymName = MutableStateFlow(sp.getString("gym_name", "") ?: "")
    private val _role = MutableStateFlow(sp.getString("role", null))
    private val _demo = MutableStateFlow(sp.getBoolean("demo", false))
    private val _theme = MutableStateFlow(sp.getString("theme", "system") ?: "system")
    private val _subStatus = MutableStateFlow(sp.getString("sub_status", null))
    private val _subUntil = MutableStateFlow(sp.getString("sub_until", null))

    val gymId: StateFlow<String?> = _gymId
    val gymName: StateFlow<String> = _gymName
    val role: StateFlow<String?> = _role
    val demo: StateFlow<Boolean> = _demo
    val theme: StateFlow<String> = _theme // system | light | dark
    val subStatus: StateFlow<String?> = _subStatus
    val subUntil: StateFlow<String?> = _subUntil

    fun setGym(id: String, name: String, role: String) {
        sp.edit().putString("gym_id", id).putString("gym_name", name).putString("role", role).apply()
        _gymId.value = id; _gymName.value = name; _role.value = role
    }

    fun setDemo(on: Boolean) { sp.edit().putBoolean("demo", on).apply(); _demo.value = on }
    fun setTheme(v: String) { sp.edit().putString("theme", v).apply(); _theme.value = v }

    fun setSubscription(status: String?, until: String?) {
        sp.edit().putString("sub_status", status).putString("sub_until", until).apply()
        _subStatus.value = status; _subUntil.value = until
    }

    fun cursor(gym: String, table: String): String? = sp.getString("cur_${gym}_$table", null)
    fun setCursor(gym: String, table: String, v: String) { sp.edit().putString("cur_${gym}_$table", v).apply() }

    fun clearAccount() {
        val keep = _theme.value
        sp.edit().clear().putString("theme", keep).apply()
        _gymId.value = null; _gymName.value = ""; _role.value = null; _demo.value = false
        _subStatus.value = null; _subUntil.value = null
    }
}

/** Auth tokens live in EncryptedSharedPreferences (Android Keystore). If that is unavailable, they stay in memory only. */
class SessionStore(ctx: Context) {
    private val sp: SharedPreferences? = open(ctx)
    private val mem = HashMap<String, String>()

    private fun get(k: String): String? = sp?.getString(k, null) ?: mem[k]
    private fun put(vararg kv: Pair<String, String?>) {
        val e = sp?.edit()
        for ((k, v) in kv) {
            if (v == null) { e?.remove(k); mem.remove(k) } else { e?.putString(k, v); if (sp == null) mem[k] = v }
        }
        e?.apply()
    }

    val accessToken: String? get() = get("at")
    val refreshToken: String? get() = get("rt")
    val userId: String? get() = get("uid")
    val email: String? get() = get("email")
    val expiresAtSec: Long get() = get("exp")?.toLongOrNull() ?: 0L

    fun save(access: String, refresh: String, expiresInSec: Long, userId: String?, email: String?) {
        put("at" to access, "rt" to refresh,
            "exp" to (System.currentTimeMillis() / 1000 + expiresInSec).toString(),
            "uid" to (userId ?: this.userId), "email" to (email ?: this.email))
    }

    fun clear() { sp?.edit()?.clear()?.apply(); mem.clear() }

    private companion object {
        fun open(ctx: Context): SharedPreferences? {
            fun create(): SharedPreferences {
                val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    ctx, "hisab_session", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try { create() } catch (e: Exception) {
                try { ctx.deleteSharedPreferences("hisab_session"); create() } catch (e2: Exception) { null }
            }
        }
    }
}

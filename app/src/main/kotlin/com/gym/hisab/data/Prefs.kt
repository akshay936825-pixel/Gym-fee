package com.gym.hisab.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small local settings. Nothing here is a secret and nothing is synced.
 * Existing installs keep their stored gym id, so existing Room rows stay visible.
 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("hisab_prefs", Context.MODE_PRIVATE)

    init {
        // First run (or an install that had signed out): a single local gym, no account needed.
        if (sp.getString(KEY_GYM_ID, null).isNullOrBlank()) {
            sp.edit().putString(KEY_GYM_ID, LOCAL_GYM_ID).putString(KEY_GYM_NAME, DEFAULT_GYM_NAME).apply()
        }
    }

    private val _gymId = MutableStateFlow(sp.getString(KEY_GYM_ID, LOCAL_GYM_ID))
    private val _gymName = MutableStateFlow(sp.getString(KEY_GYM_NAME, DEFAULT_GYM_NAME)?.ifBlank { DEFAULT_GYM_NAME } ?: DEFAULT_GYM_NAME)
    private val _theme = MutableStateFlow(sp.getString("theme", "system") ?: "system")
    private val _lastBackup = MutableStateFlow(sp.getLong(KEY_LAST_BACKUP, 0L))

    val gymId: StateFlow<String?> = _gymId
    val gymName: StateFlow<String> = _gymName
    val theme: StateFlow<String> = _theme // system | light | dark
    /** Epoch millis of the last successful manual backup, 0 = never. */
    val lastBackupAt: StateFlow<Long> = _lastBackup

    var plansSeeded: Boolean
        get() = sp.getBoolean("plans_seeded", false)
        set(v) { sp.edit().putBoolean("plans_seeded", v).apply() }

    fun setTheme(v: String) { sp.edit().putString("theme", v).apply(); _theme.value = v }

    fun setGymName(name: String) {
        val n = name.trim().ifBlank { DEFAULT_GYM_NAME }
        sp.edit().putString(KEY_GYM_NAME, n).apply(); _gymName.value = n
    }

    fun setLastBackup(millis: Long) { sp.edit().putLong(KEY_LAST_BACKUP, millis).apply(); _lastBackup.value = millis }

    companion object {
        const val LOCAL_GYM_ID = "local-gym"
        const val DEFAULT_GYM_NAME = "My Gym"
        private const val KEY_GYM_ID = "gym_id"
        private const val KEY_GYM_NAME = "gym_name"
        private const val KEY_LAST_BACKUP = "last_backup_at"
    }
}

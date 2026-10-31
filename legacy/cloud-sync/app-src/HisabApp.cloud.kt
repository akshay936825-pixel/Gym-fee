package com.gym.hisab

import android.app.Application
import androidx.room.Room
import com.gym.hisab.data.Prefs
import com.gym.hisab.data.Repository
import com.gym.hisab.data.SessionStore
import com.gym.hisab.data.local.HisabDb
import com.gym.hisab.data.remote.SupabaseApi
import com.gym.hisab.work.Notifications
import com.gym.hisab.work.Scheduler

/** Tiny hand-written dependency container (no DI framework = fewer build moving parts). */
class AppContainer(app: Application) {
    val prefs = Prefs(app)
    val session = SessionStore(app)
    val db: HisabDb = Room.databaseBuilder(app, HisabDb::class.java, "hisab.db").build()
    val api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, session)
    val repo = Repository(db, api, prefs, session) { Scheduler.kickSync(app) }
}

class HisabApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannel(this)
        Scheduler.schedulePeriodic(this)
    }
}

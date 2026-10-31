package com.gym.hisab

import android.app.Application
import com.gym.hisab.data.Prefs
import com.gym.hisab.data.Repository
import com.gym.hisab.data.backup.BackupManager
import com.gym.hisab.data.local.HisabDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.gym.hisab.sync.SheetsSyncScheduler

/** Tiny dependency container. Room remains local source of truth; WorkManager mirrors snapshots to Sheets. */
class AppContainer(private val app: Application) {
    val prefs: Prefs by lazy { Prefs(app) }
    val db: HisabDb by lazy { HisabDb.create(app) }
    val repo: Repository by lazy { Repository(db, prefs) { SheetsSyncScheduler.requestNow(app) } }
    val backup: BackupManager by lazy { BackupManager(app, db, prefs) }
}

class HisabApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // The only startup work: open the database and seed first-run data on a background
        // thread, so the first query from the UI does not pay for opening the file.
        SheetsSyncScheduler.schedule(this)
        appScope.launch {
            try { container.repo.bootstrapLocal() } catch (_: Exception) { /* UI still works; it retries on first write */ }
        }
    }
}

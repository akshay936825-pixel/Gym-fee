package com.gym.hisab.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.gym.hisab.data.Prefs
import com.gym.hisab.data.local.HisabDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

data class RestoreResult(val added: Int, val alreadyPresent: Int)

/**
 * Manual backup / restore. Runs only when the owner taps a button; it is never used at startup
 * and never touches the network. Restore only ADDS missing rows: it never overwrites or deletes
 * anything that is already in the database.
 */
class BackupManager(
    private val ctx: Context,
    private val db: HisabDb,
    private val prefs: Prefs,
) {
    private val dao = db.dao()

    /** Consistent snapshot of every table (one read transaction). */
    suspend fun snapshot(): Snapshot = db.withTransaction {
        Snapshot(
            gymName = prefs.gymName.value,
            plans = dao.allPlans(), members = dao.allMembers(), memberships = dao.allMemberships(),
            payments = dao.allPayments(), expenses = dao.allExpenses(), attendance = dao.allAttendance(),
        )
    }

    /** Writes the backup to [uri], then reads it back and verifies it before reporting success. */
    suspend fun exportTo(uri: Uri): Snapshot = withContext(Dispatchers.IO) {
        val snap = snapshot()
        val text = BackupCodec.encode(snap, Instant.now().toString())
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: throw BackupException("Could not open the chosen location for writing.")
        val back = readText(uri)
        val check = BackupCodec.decode(back).snapshot
        val verified = check.plans.size == snap.plans.size &&
            check.members.size == snap.members.size &&
            check.memberships.size == snap.memberships.size &&
            check.payments.size == snap.payments.size &&
            check.expenses.size == snap.expenses.size &&
            check.attendance.size == snap.attendance.size
        if (!verified) {
            throw BackupException("The saved file did not verify. Please try again.")
        }
        prefs.setLastBackup(System.currentTimeMillis())
        snap
    }

    /** Reads and validates a backup without changing anything (used for the confirmation dialog). */
    suspend fun preview(uri: Uri): BackupInfo = withContext(Dispatchers.IO) { BackupCodec.decode(readText(uri)) }

    /**
     * Adds every row that is missing. A safety copy of the current data is written first.
     * The whole restore is one transaction: it either completes or changes nothing.
     */
    suspend fun restore(info: BackupInfo): RestoreResult = withContext(Dispatchers.IO) {
        writeSafetyCopy()
        val g = prefs.gymId.value ?: Prefs.LOCAL_GYM_ID
        val s = info.snapshot
        val added = db.withTransaction {
            // Single-gym app: rows always belong to this phone's gym id, so they are always visible.
            val r = listOf(
                dao.insertPlansIfAbsent(s.plans.map { it.copy(gymId = g) }),
                dao.insertMembersIfAbsent(s.members.map { it.copy(gymId = g) }),
                dao.insertMembershipsIfAbsent(s.memberships.map { it.copy(gymId = g) }),
                dao.insertPaymentsIfAbsent(s.payments.map { it.copy(gymId = g) }),
                dao.insertExpensesIfAbsent(s.expenses.map { it.copy(gymId = g) }),
                dao.insertAttendanceIfAbsent(s.attendance.map { it.copy(gymId = g) }),
            )
            r.sumOf { list -> list.count { it != -1L } }
        }
        val total = s.plans.size + s.members.size + s.memberships.size + s.payments.size + s.expenses.size + s.attendance.size
        if (prefs.gymName.value == Prefs.DEFAULT_GYM_NAME && s.gymName.isNotBlank()) prefs.setGymName(s.gymName)
        RestoreResult(added = added, alreadyPresent = total - added)
    }

    private suspend fun writeSafetyCopy() {
        val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val f = File(dir, "before-restore-${System.currentTimeMillis()}.json")
        f.writeText(BackupCodec.encode(snapshot(), Instant.now().toString()), Charsets.UTF_8)
        // Keep only the 5 newest safety copies.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
    }

    private fun readText(uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw BackupException("Could not open that file.")
}

package com.gym.hisab.data.backup

import com.gym.hisab.data.local.AttendanceE
import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.data.local.MemberE
import com.gym.hisab.data.local.MembershipE
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.data.local.PlanE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Every table the app owns, as plain lists. Pure data: no Android, easy to unit test. */
@Serializable
data class Snapshot(
    val gymName: String = "",
    val plans: List<PlanE> = emptyList(),
    val members: List<MemberE> = emptyList(),
    val memberships: List<MembershipE> = emptyList(),
    val payments: List<PaymentE> = emptyList(),
    val expenses: List<ExpenseE> = emptyList(),
    val attendance: List<AttendanceE> = emptyList(),
)

@Serializable
private data class Envelope(
    val format: String,
    val version: Int,
    @SerialName("created_at") val createdAt: String,
    val checksum: String,
    val body: Snapshot,
)

class BackupException(message: String) : Exception(message)

data class BackupInfo(val createdAt: String, val snapshot: Snapshot)

/**
 * Backup file = JSON with a SHA-256 checksum of the data, so a half-written, truncated or hand-edited
 * file is detected and refused instead of being restored as wrong numbers.
 */
object BackupCodec {
    const val FORMAT = "gym-hisab-backup"
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    fun encode(snapshot: Snapshot, createdAt: String): String {
        val body = json.encodeToString(Snapshot.serializer(), snapshot)
        return json.encodeToString(Envelope.serializer(), Envelope(FORMAT, VERSION, createdAt, sha256(body), snapshot))
    }

    fun decode(text: String): BackupInfo {
        val env = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (e: Exception) {
            throw BackupException("This is not a Gym Hisab backup file, or it is damaged.")
        }
        if (env.format != FORMAT) throw BackupException("This is not a Gym Hisab backup file.")
        if (env.version > VERSION) throw BackupException("This backup was made by a newer version of the app. Update the app first.")
        val body = json.encodeToString(Snapshot.serializer(), env.body)
        if (sha256(body) != env.checksum) throw BackupException("The backup file is damaged or was edited. Nothing was changed.")
        return BackupInfo(env.createdAt, env.body)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

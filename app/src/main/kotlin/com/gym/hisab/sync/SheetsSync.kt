package com.gym.hisab.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gym.hisab.data.local.HisabDb
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Full, idempotent snapshot upload. Room remains the source of truth; Sheets is a backup/reporting mirror. */
object SheetsSyncConfig {
    val json = Json { encodeDefaults = true; explicitNulls = false }
    const val ENDPOINT = "https://script.google.com/macros/s/AKfycbzDKRh4DJAQTuEIwpvq7WPRwZAY7Q1bsXOswzI-74yhxCaUYado3CIqq5ipNxaTU-8mDA/exec"
    const val TOKEN = "093af4624f9fd40c6d20090e55d904598fc83cb46f16caeaab48bcd471401236"
    const val PERIODIC_WORK = "gym_hisab_sheets_periodic"
    const val IMMEDIATE_WORK = "gym_hisab_sheets_immediate"
}

object SheetsSyncScheduler {
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<SheetsSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SheetsSyncConfig.PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        requestNow(context)
    }

    fun requestNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SheetsSyncWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SheetsSyncConfig.IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request
        )
    }
}

class SheetsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val database = HisabDb.create(applicationContext)
            val dao = database.dao()
            val payload = buildJsonObject {
                put("action", "syncSnapshot")
                put("token", SheetsSyncConfig.TOKEN)
                put("data", buildJsonObject {
                    put("plans", SheetsSyncConfig.json.encodeToJsonElement(dao.allPlans()))
                    put("members", SheetsSyncConfig.json.encodeToJsonElement(dao.allMembers()))
                    put("memberships", SheetsSyncConfig.json.encodeToJsonElement(dao.allMemberships()))
                    put("payments", SheetsSyncConfig.json.encodeToJsonElement(dao.allPayments()))
                    put("expenses", SheetsSyncConfig.json.encodeToJsonElement(dao.allExpenses()))
                    put("attendance", SheetsSyncConfig.json.encodeToJsonElement(dao.allAttendance()))
                })
            }
            database.close()
            val connection = (URL(SheetsSyncConfig.ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val body = Json.encodeToString(payload)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (status !in 200..299) return if (status >= 500 || status == 429) Result.retry() else Result.failure()
            val result = try { Json.parseToJsonElement(response).jsonObject } catch (_: Exception) { null }
            val ok = result?.get("ok")?.jsonPrimitive?.content == "true" &&
                result?.get("protocol")?.jsonPrimitive?.content == "gym-hisab-v2"
            if (ok) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

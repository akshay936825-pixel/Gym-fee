package com.gym.hisab.data.remote

import com.gym.hisab.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

class ApiException(val code: Int, message: String, val offline: Boolean = false) : Exception(message)

@Serializable
private data class AuthUser(val id: String? = null, val email: String? = null)

@Serializable
private data class AuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val user: AuthUser? = null,
)

/**
 * Minimal Supabase client (Auth + PostgREST + Edge Functions) over OkHttp.
 * It only ever holds the PUBLIC anon key plus the signed-in user's own token.
 */
class SupabaseApi(
    private val baseUrl: String,
    private val anonKey: String,
    private val session: SessionStore,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private val refreshLock = Mutex()
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    // ---------- auth ----------
    suspend fun signIn(email: String, password: String) {
        val text = raw("POST", "/auth/v1/token?grant_type=password",
            """{"email":${q(email)},"password":${q(password)}}""", auth = false)
        saveAuth(AppJson.decodeFromString(AuthResponse.serializer(), text))
    }

    /** Returns true when signed in right away, false when the project requires e-mail confirmation first. */
    suspend fun signUp(email: String, password: String, name: String): Boolean {
        val text = raw("POST", "/auth/v1/signup",
            """{"email":${q(email)},"password":${q(password)},"data":{"name":${q(name)}}}""", auth = false)
        val r = AppJson.decodeFromString(AuthResponse.serializer(), text)
        if (r.accessToken.isNullOrBlank()) return false
        saveAuth(r)
        return true
    }

    suspend fun updatePassword(newPassword: String) {
        raw("PUT", "/auth/v1/user", """{"password":${q(newPassword)}}""")
    }

    suspend fun signOut() {
        try { raw("POST", "/auth/v1/logout", "{}") } catch (_: Exception) { }
        session.clear()
    }

    private fun saveAuth(r: AuthResponse) {
        session.save(r.accessToken ?: return, r.refreshToken ?: "", r.expiresIn, r.user?.id, r.user?.email)
    }

    private suspend fun refresh(): Boolean = refreshLock.withLock {
        val rt = session.refreshToken
        if (rt.isNullOrBlank()) return@withLock false
        try {
            val text = raw("POST", "/auth/v1/token?grant_type=refresh_token", """{"refresh_token":${q(rt)}}""", auth = false)
            saveAuth(AppJson.decodeFromString(AuthResponse.serializer(), text))
            true
        } catch (e: ApiException) {
            if (!e.offline && e.code in 400..499) session.clear()
            false
        }
    }

    val signedIn: Boolean get() = !session.accessToken.isNullOrBlank()
    val userId: String? get() = session.userId
    val email: String? get() = session.email

    // ---------- data ----------
    suspend fun get(path: String): String = call("GET", "/rest/v1/$path", null)

    suspend fun upsert(table: String, bodyJson: String, ignoreDuplicates: Boolean) {
        val prefer = (if (ignoreDuplicates) "resolution=ignore-duplicates" else "resolution=merge-duplicates") + ",return=minimal"
        call("POST", "/rest/v1/$table?on_conflict=id", bodyJson, mapOf("Prefer" to prefer))
    }

    suspend fun patch(path: String, bodyJson: String) {
        call("PATCH", "/rest/v1/$path", bodyJson, mapOf("Prefer" to "return=minimal"))
    }

    suspend fun rpc(name: String, bodyJson: String): String = call("POST", "/rest/v1/rpc/$name", bodyJson)

    suspend fun function(name: String, bodyJson: String): JsonObject =
        AppJson.parseToJsonElement(call("POST", "/functions/v1/$name", bodyJson)).jsonObject

    // ---------- plumbing ----------
    private suspend fun call(method: String, path: String, body: String?, extra: Map<String, String> = emptyMap()): String {
        try {
            return raw(method, path, body, extra)
        } catch (e: ApiException) {
            if (e.code == 401 && refresh()) return raw(method, path, body, extra)
            throw e
        }
    }

    private suspend fun raw(
        method: String, path: String, body: String?,
        extra: Map<String, String> = emptyMap(), auth: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        if (!configured) throw ApiException(0, "Backend is not configured", offline = true)
        val b = Request.Builder().url(url(path)).header("apikey", anonKey)
        // New-style publishable keys ("sb_publishable_...") are not JWTs, so they go only in the apikey header.
        val bearer = (if (auth) session.accessToken else null) ?: anonKey.takeUnless { it.startsWith("sb_") }
        if (bearer != null) b.header("Authorization", "Bearer $bearer")
        for ((k, v) in extra) b.header(k, v)
        val rb = if (method == "GET") null else (body ?: "{}").toRequestBody(jsonType)
        b.method(method, rb)
        try {
            http.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) text else throw ApiException(resp.code, message(text, resp.code))
            }
        } catch (e: IOException) {
            throw ApiException(0, "No internet connection", offline = true)
        }
    }

    private fun message(text: String, code: Int): String = try {
        val o = AppJson.parseToJsonElement(text).jsonObject
        listOf("error_description", "msg", "message", "error").firstNotNullOfOrNull { k ->
            o[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        } ?: "Request failed ($code)"
    } catch (e: Exception) {
        "Request failed ($code)"
    }

    private fun q(s: String): String = AppJson.encodeToString(kotlinx.serialization.serializer<String>(), s)
}

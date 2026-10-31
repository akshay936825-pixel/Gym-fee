@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gym.hisab.data

import androidx.room.withTransaction
import com.gym.hisab.core.Dates
import com.gym.hisab.core.Ids
import com.gym.hisab.core.Ledger
import com.gym.hisab.data.local.AttendanceE
import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.data.local.HisabDb
import com.gym.hisab.data.local.MemberE
import com.gym.hisab.data.local.MembershipE
import com.gym.hisab.data.local.OutboxE
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.data.local.PlanE
import com.gym.hisab.data.remote.ApiException
import com.gym.hisab.data.remote.AppJson
import com.gym.hisab.data.remote.SupabaseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime

sealed interface SyncUi {
    data object Idle : SyncUi
    data object Syncing : SyncUi
    data class Done(val atMillis: Long) : SyncUi
    data object Offline : SyncUi
    data class Error(val message: String) : SyncUi
}

@Serializable data class GymRow(val name: String = "", val phone: String? = null, val city: String? = null)
@Serializable data class GymUserRow(
    @SerialName("gym_id") val gymId: String,
    val role: String,
    @SerialName("display_name") val displayName: String = "",
    val gyms: GymRow? = null,
)
@Serializable data class SubRow(
    val plan: String = "trial",
    val status: String = "expired",
    @SerialName("current_period_end") val currentPeriodEnd: String? = null,
)
@Serializable data class StaffRow(
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("display_name") val displayName: String = "",
    val email: String? = null,
    val active: Boolean = true,
)

/**
 * Offline-first data layer. Every change is written to Room immediately (the UI reads Room),
 * then queued in the outbox and pushed to Supabase whenever the network allows.
 * Supabase remains the source of truth and the only place permissions/subscriptions are enforced.
 */
class Repository(
    private val db: HisabDb,
    private val api: SupabaseApi,
    private val prefs: Prefs,
    private val session: SessionStore,
    private val onChange: () -> Unit,
) {
    private val dao = db.dao()
    private val syncLock = Mutex()

    val gymId: StateFlow<String?> = prefs.gymId
    val gymName: StateFlow<String> = prefs.gymName
    val role: StateFlow<String?> = prefs.role
    val demo: StateFlow<Boolean> = prefs.demo
    val backendConfigured: Boolean get() = api.configured
    val signedIn: Boolean get() = api.signedIn
    val email: String? get() = api.email

    private val _sync = MutableStateFlow<SyncUi>(SyncUi.Idle)
    val syncState: StateFlow<SyncUi> = _sync

    private fun <T> byGym(empty: T, f: (String) -> Flow<T>): Flow<T> =
        prefs.gymId.flatMapLatest { g -> if (g == null) flowOf(empty) else f(g) }

    val members: Flow<List<MemberE>> = byGym(emptyList()) { dao.members(it) }
    val memberships: Flow<List<MembershipE>> = byGym(emptyList()) { dao.memberships(it) }
    val payments: Flow<List<PaymentE>> = byGym(emptyList()) { dao.payments(it) }
    val expenses: Flow<List<ExpenseE>> = byGym(emptyList()) { dao.expenses(it) }
    val plans: Flow<List<PlanE>> = byGym(emptyList()) { dao.plans(it) }
    fun attendanceOn(day: String): Flow<List<AttendanceE>> = byGym(emptyList()) { dao.attendanceOn(it, day) }
    fun attendanceOfMember(memberId: String): Flow<List<AttendanceE>> = byGym(emptyList()) { dao.attendanceOfMember(it, memberId) }
    val pendingCount: Flow<Int> = dao.pendingCount()
    val failedCount: Flow<Int> = dao.failedCount()

    private fun gym(): String = prefs.gymId.value ?: throw IllegalStateException("No gym selected")
    private suspend fun queue(table: String, vararg ids: String) {
        if (prefs.demo.value) return
        for (id in ids) dao.enqueue(OutboxE(tableName = table, rowId = id))
    }

    // ---------------------------------------------------------------- subscription (display only)
    /** UI hint only. The server rejects writes from an expired gym regardless of what the phone thinks. */
    fun canWrite(): Boolean {
        if (prefs.demo.value) return true
        val st = prefs.subStatus.value ?: return true
        if (st != "active" && st != "trialing") return false
        val until = prefs.subUntil.value ?: return true
        return try { OffsetDateTime.parse(until).isAfter(OffsetDateTime.now()) } catch (e: Exception) { true }
    }

    suspend fun refreshSubscription(g: String = gym()) {
        val rows = AppJson.decodeFromString(ListSerializer(SubRow.serializer()), api.get("subscriptions?select=*&gym_id=eq.$g"))
        val r = rows.firstOrNull()
        prefs.setSubscription(r?.status ?: "expired", r?.currentPeriodEnd)
    }

    // ---------------------------------------------------------------- account
    suspend fun signIn(email: String, password: String) = api.signIn(email, password)
    suspend fun signUp(email: String, password: String, name: String): Boolean = api.signUp(email, password, name)
    suspend fun changePassword(newPassword: String) = api.updatePassword(newPassword)

    /** @return true if this user already belongs to a gym, false if they still need to create one. */
    suspend fun loadAccount(): Boolean {
        val uid = api.userId ?: throw ApiException(401, "Please sign in")
        val text = api.get("gym_users?select=gym_id,role,display_name,gyms(name,phone,city)&user_id=eq.$uid&active=eq.true")
        val rows = AppJson.decodeFromString(ListSerializer(GymUserRow.serializer()), text)
        if (rows.isEmpty()) return false
        val saved = prefs.gymId.value
        val pick = rows.firstOrNull { it.gymId == saved } ?: rows.first()
        // A different account/gym signed in on this phone: never leave the previous gym's data behind.
        if (saved != null && saved != pick.gymId) withContext(Dispatchers.IO) { db.clearAllTables() }
        prefs.setGym(pick.gymId, pick.gyms?.name ?: "My Gym", pick.role)
        try { refreshSubscription(pick.gymId) } catch (e: ApiException) { /* keep going offline */ }
        return true
    }

    suspend fun createGym(name: String, phone: String, city: String) {
        val body = buildJsonObject { put("p_name", name.trim()); put("p_phone", phone.trim()); put("p_city", city.trim()) }.toString()
        val id = api.rpc("create_gym", body).trim().trim('"')
        prefs.setGym(id, name.trim(), "owner")
        try { refreshSubscription(id) } catch (e: ApiException) { }
        seedDefaultPlans(id)
        onChange()
    }

    private suspend fun seedDefaultPlans(g: String) {
        val defaults = listOf(
            Triple("Monthly", 1, 100000L), Triple("Quarterly", 3, 270000L),
            Triple("Half-yearly", 6, 500000L), Triple("Annual", 12, 900000L),
        ).map { PlanE(Ids.newId(), g, it.first, it.second, it.third) }
        db.withTransaction {
            dao.upsertPlans(defaults)
            queue("plans", *defaults.map { it.id }.toTypedArray())
        }
    }

    suspend fun signOut() {
        if (!prefs.demo.value && api.configured) api.signOut()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        session.clear()
        prefs.clearAccount()
        _sync.value = SyncUi.Idle
    }

    // ---------------------------------------------------------------- members & money
    suspend fun addMember(
        name: String, phone: String?, plan: PlanE?, startOn: LocalDate,
        discountPaise: Long, paidPaise: Long, mode: String,
    ): PaymentE? {
        val g = gym()
        val member = MemberE(Ids.newId(), g, name.trim(), phone?.trim()?.ifBlank { null }, startOn.toString())
        val result = db.withTransaction {
            dao.upsertMembers(listOf(member)); queue("members", member.id)
            if (plan != null) sellMembership(g, member.id, plan, startOn, discountPaise, paidPaise, mode) else null
        }
        onChange()
        return result
    }

    suspend fun renew(memberId: String, plan: PlanE, startOn: LocalDate, discountPaise: Long, paidPaise: Long, mode: String): PaymentE? {
        val g = gym()
        val result = db.withTransaction { sellMembership(g, memberId, plan, startOn, discountPaise, paidPaise, mode) }
        onChange()
        return result
    }

    private suspend fun sellMembership(
        g: String, memberId: String, plan: PlanE, startOn: LocalDate,
        discountPaise: Long, paidPaise: Long, mode: String,
    ): PaymentE? {
        val discount = discountPaise.coerceIn(0L, plan.pricePaise)
        val ms = MembershipE(
            id = Ids.newId(), gymId = g, memberId = memberId, planId = plan.id, planName = plan.name,
            startOn = startOn.toString(), endOn = Ledger.endDate(startOn, plan.durationMonths).toString(),
            pricePaise = plan.pricePaise, discountPaise = discount,
        )
        dao.upsertMemberships(listOf(ms)); queue("memberships", ms.id)
        val pay = paidPaise.coerceIn(0L, plan.pricePaise - discount)
        if (pay <= 0) return null
        return writePayment(g, memberId, ms.id, Ledger.PAYMENT, pay, mode, null)
    }

    suspend fun collect(memberId: String, membershipId: String?, amountPaise: Long, mode: String, note: String?): PaymentE {
        require(amountPaise > 0) { "Amount must be more than zero" }
        val g = gym()
        val p = db.withTransaction { writePayment(g, memberId, membershipId, Ledger.PAYMENT, amountPaise, mode, note) }
        onChange()
        return p
    }

    suspend fun refund(memberId: String, membershipId: String?, amountPaise: Long, mode: String, note: String?): PaymentE {
        require(amountPaise > 0) { "Amount must be more than zero" }
        val g = gym()
        val p = db.withTransaction { writePayment(g, memberId, membershipId, Ledger.REFUND, amountPaise, mode, note) }
        onChange()
        return p
    }

    private suspend fun writePayment(g: String, memberId: String, membershipId: String?, kind: String, amount: Long, mode: String, note: String?): PaymentE {
        val today = Dates.today()
        val p = PaymentE(
            id = Ids.newId(), gymId = g, memberId = memberId, membershipId = membershipId, kind = kind,
            amountPaise = amount, mode = mode, paidOn = today.toString(), note = note?.trim()?.ifBlank { null },
            receiptNo = Ids.receiptNo(today),
        )
        dao.upsertPayments(listOf(p)); queue("payments", p.id)
        return p
    }

    suspend fun updateMember(m: MemberE) {
        db.withTransaction { dao.upsertMembers(listOf(m)); queue("members", m.id) }
        onChange()
    }

    suspend fun cancelMembership(ms: MembershipE) {
        val today = Dates.today().toString()
        val newEnd = maxOf(ms.startOn, minOf(ms.endOn, today))
        db.withTransaction {
            val c = ms.copy(status = Ledger.CANCELLED, endOn = newEnd)
            dao.upsertMemberships(listOf(c)); queue("memberships", c.id)
        }
        onChange()
    }

    // ---------------------------------------------------------------- expenses, attendance, plans
    suspend fun addExpense(category: String, amountPaise: Long, spentOn: LocalDate, note: String?) {
        val e = ExpenseE(Ids.newId(), gym(), category.trim(), amountPaise, spentOn.toString(), note?.trim()?.ifBlank { null })
        db.withTransaction { dao.upsertExpenses(listOf(e)); queue("expenses", e.id) }
        onChange()
    }

    suspend fun deleteExpense(e: ExpenseE) {
        db.withTransaction { dao.upsertExpenses(listOf(e.copy(deleted = true))); queue("expenses", e.id) }
        onChange()
    }

    suspend fun setAttendance(memberId: String, day: String, present: Boolean) {
        val g = gym()
        val a = AttendanceE(Ids.attendanceId(g, memberId, day), g, memberId, day, present)
        db.withTransaction { dao.upsertAttendance(listOf(a)); queue("attendance", a.id) }
        onChange()
    }

    suspend fun savePlan(p: PlanE) {
        val fixed = if (p.gymId.isBlank()) p.copy(gymId = gym()) else p
        db.withTransaction { dao.upsertPlans(listOf(fixed)); queue("plans", fixed.id) }
        onChange()
    }

    // ---------------------------------------------------------------- sync
    suspend fun syncNow(): Boolean = syncLock.withLock {
        val g = prefs.gymId.value
        if (prefs.demo.value || !api.configured || !api.signedIn || g == null) return@withLock true
        _sync.value = SyncUi.Syncing
        try {
            pushAll()
            pull(g, "plans", PlanE.serializer(), { it.updatedAt }, { it.id }) { dao.upsertPlans(it) }
            pull(g, "members", MemberE.serializer(), { it.updatedAt }, { it.id }) { dao.upsertMembers(it) }
            pull(g, "memberships", MembershipE.serializer(), { it.updatedAt }, { it.id }) { dao.upsertMemberships(it) }
            pull(g, "payments", PaymentE.serializer(), { it.updatedAt }, { it.id }) { dao.upsertPayments(it) }
            if (prefs.role.value != "staff") {
                pull(g, "expenses", ExpenseE.serializer(), { it.updatedAt }, { it.id }) { dao.upsertExpenses(it) }
            }
            pull(g, "attendance", AttendanceE.serializer(), { it.updatedAt }, { it.id }) { dao.upsertAttendance(it) }
            try { refreshSubscription(g) } catch (e: ApiException) { if (e.offline) throw e }
            val failed = dao.firstFailureMessage()
            _sync.value = if (failed != null) SyncUi.Error(failed) else SyncUi.Done(System.currentTimeMillis())
            failed == null
        } catch (e: ApiException) {
            _sync.value = if (e.offline) SyncUi.Offline else SyncUi.Error(e.message ?: "Sync failed")
            false
        }
    }

    suspend fun retryFailed() { dao.retryFailed(); onChange() }

    private suspend fun pushAll() {
        for (ob in dao.pending()) {
            try {
                pushRow(ob)
                dao.dropOutbox(ob.seq)
            } catch (e: ApiException) {
                // Network trouble, server trouble, or an expired login: stop and retry later, in order.
                if (e.offline || e.code >= 500 || e.code == 401) throw e
                // Anything else (permission, expired subscription, validation) will not fix itself: flag it.
                dao.markOutbox(ob.seq, friendly(e), true)
            }
        }
    }

    private fun friendly(e: ApiException): String = when (e.code) {
        402, 403 -> "Not allowed. Your subscription may have expired, or your role cannot do this."
        409 -> "This record conflicts with data already on the server."
        else -> e.message ?: "Could not upload"
    }

    private suspend fun pushRow(ob: OutboxE) {
        val id = ob.rowId
        when (ob.tableName) {
            "plans" -> dao.plan(id)?.let { api.upsert("plans", AppJson.encodeToString(PlanE.serializer(), it), false) }
            "members" -> dao.member(id)?.let { api.upsert("members", AppJson.encodeToString(MemberE.serializer(), it), false) }
            "memberships" -> dao.membership(id)?.let { api.upsert("memberships", AppJson.encodeToString(MembershipE.serializer(), it), false) }
            "payments" -> dao.payment(id)?.let { api.upsert("payments", AppJson.encodeToString(PaymentE.serializer(), it), true) }
            "expenses" -> dao.expense(id)?.let { api.upsert("expenses", AppJson.encodeToString(ExpenseE.serializer(), it), false) }
            "attendance" -> dao.attendance(id)?.let { api.upsert("attendance", AppJson.encodeToString(AttendanceE.serializer(), it), false) }
        }
    }

    private suspend fun <T> pull(
        g: String, table: String, ser: KSerializer<T>,
        updatedAt: (T) -> String?, idOf: (T) -> String, save: suspend (List<T>) -> Unit,
    ) {
        val unsynced = dao.outboxRowIds(table).toSet() // never overwrite a local change that has not been uploaded yet
        var cursor = prefs.cursor(g, table)
        var first = true
        while (true) {
            val from = if (first) cursor?.let { lagged(it) } else cursor
            first = false
            val q = StringBuilder("$table?select=*&gym_id=eq.$g&order=updated_at.asc,id.asc&limit=$PAGE")
            if (from != null) q.append("&updated_at=gte.").append(URLEncoder.encode(from, "UTF-8"))
            val rows = AppJson.decodeFromString(ListSerializer(ser), api.get(q.toString()))
            val fresh = rows.filter { idOf(it) !in unsynced }
            if (fresh.isNotEmpty()) save(fresh)
            val last = rows.lastOrNull()?.let(updatedAt)
            if (last != null) {
                if (last == cursor && rows.size >= PAGE) break
                cursor = last
                prefs.setCursor(g, table, last)
            }
            if (rows.size < PAGE) break
        }
    }

    /** Re-read the last 10 seconds so a slow transaction that committed late is never missed. */
    private fun lagged(cursor: String): String =
        try { OffsetDateTime.parse(cursor).minusSeconds(10).toString() } catch (e: Exception) { cursor }

    // ---------------------------------------------------------------- team, subscription, AI, Sheets
    suspend fun staff(): List<StaffRow> {
        val text = api.get("gym_users?select=user_id,role,display_name,email,active&gym_id=eq.${gym()}&order=created_at.asc")
        return AppJson.decodeFromString(ListSerializer(StaffRow.serializer()), text)
    }

    suspend fun createStaff(name: String, email: String, password: String, role: String) {
        val body = buildJsonObject { put("gym_id", gym()); put("name", name); put("email", email); put("password", password); put("role", role) }
        api.function("invite-staff", body.toString())
    }

    suspend fun setStaffActive(userId: String, active: Boolean) {
        api.patch("gym_users?gym_id=eq.${gym()}&user_id=eq.$userId", """{"active":$active}""")
    }

    suspend fun checkoutUrl(plan: String): String {
        val body = buildJsonObject { put("gym_id", gym()); put("plan", plan) }
        return api.function("razorpay-create-link", body.toString())["url"]?.jsonPrimitive?.content
            ?: throw ApiException(502, "Could not start the payment")
    }

    suspend fun askAi(question: String): String {
        val body = buildJsonObject { put("gym_id", gym()); put("question", question) }
        return api.function("ai-assistant", body.toString())["answer"]?.jsonPrimitive?.content
            ?: throw ApiException(502, "No answer received")
    }

    suspend fun sheetsEmail(): String {
        val body = buildJsonObject { put("gym_id", gym()); put("action", "info") }
        return api.function("sheets-sync", body.toString())["service_account_email"]?.jsonPrimitive?.content ?: ""
    }

    suspend fun sheetsConnect(link: String) {
        val body = buildJsonObject { put("gym_id", gym()); put("action", "connect"); put("link", link) }
        api.function("sheets-sync", body.toString())
    }

    suspend fun sheetsSync() {
        val body = buildJsonObject { put("gym_id", gym()); put("action", "sync") }
        api.function("sheets-sync", body.toString())
    }

    // ---------------------------------------------------------------- demo mode (clearly labelled sample data)
    suspend fun startDemo() {
        val g = "demo-gym"
        prefs.setDemo(true)
        prefs.setGym(g, "Demo Gym (sample data)", "owner")
        val today = Dates.today()
        val plans = listOf(
            PlanE("demo-plan-1", g, "Monthly", 1, 100000), PlanE("demo-plan-3", g, "Quarterly", 3, 270000),
            PlanE("demo-plan-6", g, "Half-yearly", 6, 500000), PlanE("demo-plan-12", g, "Annual", 12, 900000),
        )
        val names = listOf("Rahul Sharma", "Priya Singh", "Amit Verma", "Neha Gupta", "Vikram Yadav", "Sunita Devi",
            "Rohit Kumar", "Anjali Mishra", "Deepak Patel", "Pooja Joshi", "Manoj Tiwari", "Kavita Rao")
        val members = ArrayList<MemberE>(); val ms = ArrayList<MembershipE>(); val pays = ArrayList<PaymentE>()
        names.forEachIndexed { i, n ->
            val plan = plans[i % plans.size]
            val start = today.minusDays((i * 9L) % 70)
            val m = MemberE("demo-m-$i", g, n, "98765" + (10000 + i * 137).toString(), start.toString())
            val s = MembershipE("demo-ms-$i", g, m.id, plan.id, plan.name, start.toString(),
                Ledger.endDate(start, plan.durationMonths).toString(), plan.pricePaise, 0)
            val paid = when (i % 3) { 0 -> plan.pricePaise; 1 -> plan.pricePaise / 2; else -> 0L }
            members += m; ms += s
            if (paid > 0) pays += PaymentE("demo-p-$i", g, m.id, s.id, Ledger.PAYMENT, paid,
                if (i % 2 == 0) "upi" else "cash", start.toString(), null, "HB-DEMO-${1000 + i}")
        }
        val exp = listOf(
            ExpenseE("demo-e-1", g, "Rent", 2500000, today.withDayOfMonth(1).toString()),
            ExpenseE("demo-e-2", g, "Electricity", 480000, today.withDayOfMonth(1).toString()),
            ExpenseE("demo-e-3", g, "Equipment", 350000, today.minusDays(12).toString()),
        )
        db.withTransaction {
            dao.upsertPlans(plans); dao.upsertMembers(members); dao.upsertMemberships(ms)
            dao.upsertPayments(pays); dao.upsertExpenses(exp)
        }
    }

    companion object { private const val PAGE = 1000 }
}

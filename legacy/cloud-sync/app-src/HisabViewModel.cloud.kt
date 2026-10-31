package com.gym.hisab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gym.hisab.HisabApp
import com.gym.hisab.R
import com.gym.hisab.core.Dashboard
import com.gym.hisab.core.DashboardStats
import com.gym.hisab.core.Dates
import com.gym.hisab.core.Ledger
import com.gym.hisab.core.MemberRow
import com.gym.hisab.core.MemberRows
import com.gym.hisab.core.Receipts
import com.gym.hisab.data.SyncUi
import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.data.local.MemberE
import com.gym.hisab.data.local.MembershipE
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.data.local.PlanE
import com.gym.hisab.data.remote.ApiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Auth { LOADING, SIGNED_OUT, NEEDS_GYM, READY }

class HisabViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as HisabApp).container
    val repo = container.repo
    val prefs = container.prefs

    private val _auth = MutableStateFlow(Auth.LOADING)
    val auth: StateFlow<Auth> = _auth
    val busy = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val role: StateFlow<String?> = repo.role
    val gymName: StateFlow<String> = repo.gymName
    val demo: StateFlow<Boolean> = repo.demo
    val syncState: StateFlow<SyncUi> = repo.syncState
    val pendingCount: StateFlow<Int> = repo.pendingCount.eagerly(0)
    val failedCount: StateFlow<Int> = repo.failedCount.eagerly(0)
    val today = MutableStateFlow(Dates.today())
    val receipt = MutableStateFlow<PaymentE?>(null)
    /** Shared so Home shortcuts (e.g. "Renewals") can open the Members list already filtered. */
    val memberFilter = MutableStateFlow("all")

    private fun <T> Flow<T>.eagerly(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val members: StateFlow<List<MemberE>> = repo.members.eagerly(emptyList())
    val memberships: StateFlow<List<MembershipE>> = repo.memberships.eagerly(emptyList())
    val payments: StateFlow<List<PaymentE>> = repo.payments.eagerly(emptyList())
    val expenses: StateFlow<List<ExpenseE>> = repo.expenses.eagerly(emptyList())
    val plans: StateFlow<List<PlanE>> = repo.plans.eagerly(emptyList())

    val rows: StateFlow<List<MemberRow>> =
        combine(members, memberships, payments, today) { m, ms, p, t -> MemberRows.build(m, ms, p, t) }
            .eagerly(emptyList())

    private val core = combine(members, memberships, payments) { m, ms, p -> Triple(m, ms, p) }
    val stats: StateFlow<DashboardStats?> =
        combine(core, expenses, role, today) { c, e, r, t ->
            Dashboard.compute(c.first, c.second, c.third, e, t, canSeeMoney = r != "staff")
        }.eagerly(null)

    /** UI hint only: the server is what actually blocks writes when a subscription has expired. */
    val writable: StateFlow<Boolean> =
        combine(prefs.subStatus, prefs.subUntil, repo.demo) { _, _, _ -> repo.canWrite() }.eagerly(true)

    val isStaff: Boolean get() = role.value == "staff"
    val isOwner: Boolean get() = role.value == "owner"
    val canManage: Boolean get() = role.value == "owner" || role.value == "manager"

    init { startup() }

    private fun str(id: Int): String = getApplication<Application>().getString(id)
    fun say(msg: String) { _messages.tryEmit(msg) }

    /** Runs a suspend action, shows a friendly message on failure. */
    fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: ApiException) { say(if (e.offline) str(R.string.err_offline) else e.message ?: str(R.string.err_generic)) }
            catch (e: IllegalArgumentException) { say(e.message ?: str(R.string.err_generic)) }
            catch (e: Exception) { say(str(R.string.err_generic)) }
        }
    }

    // ---------------------------------------------------------------- startup & auth
    private fun startup() {
        when {
            repo.demo.value || (repo.gymId.value != null && repo.signedIn) -> { _auth.value = Auth.READY; sync() }
            repo.signedIn -> resolveAccount()
            else -> _auth.value = Auth.SIGNED_OUT
        }
    }

    private fun resolveAccount() {
        viewModelScope.launch {
            _auth.value = Auth.LOADING
            try {
                _auth.value = if (repo.loadAccount()) Auth.READY else Auth.NEEDS_GYM
                if (_auth.value == Auth.READY) sync()
            } catch (e: ApiException) {
                if (e.offline && repo.gymId.value != null) _auth.value = Auth.READY
                else { say(e.message ?: str(R.string.err_generic)); _auth.value = Auth.SIGNED_OUT }
            }
        }
    }

    fun signIn(email: String, password: String) = authCall { repo.signIn(email.trim(), password); resolveAccount() }

    fun signUp(email: String, password: String, name: String) = authCall {
        if (repo.signUp(email.trim(), password, name.trim())) resolveAccount() else say(str(R.string.msg_confirm_email))
    }

    private fun authCall(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try { block() }
            catch (e: ApiException) { say(if (e.offline) str(R.string.err_offline) else e.message ?: str(R.string.err_generic)) }
            catch (e: Exception) { say(str(R.string.err_generic)) }
            finally { busy.value = false }
        }
    }

    fun createGym(name: String, phone: String, city: String) = authCall {
        repo.createGym(name, phone, city)
        _auth.value = Auth.READY
        sync()
    }

    fun startDemo() = authCall { repo.startDemo(); _auth.value = Auth.READY }

    fun signOut() = act { repo.signOut(); _auth.value = Auth.SIGNED_OUT }

    fun sync() { viewModelScope.launch { today.value = Dates.today(); repo.syncNow() } }
    fun retryFailed() { viewModelScope.launch { repo.retryFailed() } }

    // ---------------------------------------------------------------- money actions (UI never touches Room directly)
    fun addMember(name: String, phone: String?, plan: PlanE?, start: LocalDate, discount: Long, paid: Long, mode: String, done: () -> Unit) = act {
        val p = repo.addMember(name, phone, plan, start, discount, paid, mode)
        if (p != null) receipt.value = p
        done()
    }

    fun renew(memberId: String, plan: PlanE, start: LocalDate, discount: Long, paid: Long, mode: String, done: () -> Unit) = act {
        val p = repo.renew(memberId, plan, start, discount, paid, mode)
        if (p != null) receipt.value = p
        done()
    }

    fun collect(memberId: String, membershipId: String?, amount: Long, mode: String, note: String?, done: () -> Unit) = act {
        receipt.value = repo.collect(memberId, membershipId, amount, mode, note)
        done()
    }

    fun refund(memberId: String, membershipId: String?, amount: Long, mode: String, note: String?, done: () -> Unit) = act {
        receipt.value = repo.refund(memberId, membershipId, amount, mode, note)
        done()
    }

    fun memberName(id: String): String = members.value.firstOrNull { it.id == id }?.name ?: ""

    fun receiptText(p: PaymentE): String {
        val ms = memberships.value.firstOrNull { it.id == p.membershipId }
        val balance = ms?.let { m -> Ledger.balance(m, payments.value.filter { it.membershipId == m.id }) }
        return Receipts.text(gymName.value, p, memberName(p.memberId), ms?.planName, balance)
    }

    fun balanceOf(ms: MembershipE): Long = Ledger.balance(ms, payments.value.filter { it.membershipId == ms.id })
}

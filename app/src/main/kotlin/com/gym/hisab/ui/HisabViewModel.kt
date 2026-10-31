package com.gym.hisab.ui

import android.app.Application
import android.net.Uri
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
import com.gym.hisab.data.backup.BackupException
import com.gym.hisab.data.backup.BackupInfo
import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.data.local.MemberE
import com.gym.hisab.data.local.MembershipE
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.data.local.PlanE
import com.gym.hisab.sync.SheetsSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

class HisabViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as HisabApp).container
    val repo = container.repo
    val prefs = container.prefs
    private val backupMgr = container.backup

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val gymName: StateFlow<String> = repo.gymName
    val today = MutableStateFlow(Dates.today())
    val receipt = MutableStateFlow<PaymentE?>(null)
    /** Shared so Home shortcuts (e.g. "Renewals") can open the Members list already filtered. */
    val memberFilter = MutableStateFlow("all")
    val busy = MutableStateFlow(false)

    // One owner on one phone: everything is allowed, writing is always possible.
    val writable: StateFlow<Boolean> = MutableStateFlow(true)
    val role: StateFlow<String?> = MutableStateFlow("owner")
    val isStaff: Boolean get() = false
    val isOwner: Boolean get() = true
    val canManage: Boolean get() = true

    private fun <T> Flow<T>.eagerly(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val members: StateFlow<List<MemberE>> = repo.members.eagerly(emptyList())
    val memberships: StateFlow<List<MembershipE>> = repo.memberships.eagerly(emptyList())
    val payments: StateFlow<List<PaymentE>> = repo.payments.eagerly(emptyList())
    val expenses: StateFlow<List<ExpenseE>> = repo.expenses.eagerly(emptyList())
    val plans: StateFlow<List<PlanE>> = repo.plans.eagerly(emptyList())

    // The heavy list/aggregate maths runs on Dispatchers.Default, never on the main thread.
    val rows: StateFlow<List<MemberRow>> =
        combine(members, memberships, payments, today) { m, ms, p, t -> MemberRows.build(m, ms, p, t) }
            .flowOn(Dispatchers.Default)
            .eagerly(emptyList())

    private val core = combine(members, memberships, payments) { m, ms, p -> Triple(m, ms, p) }
    val stats: StateFlow<DashboardStats?> =
        combine(core, expenses, today) { c, e, t ->
            Dashboard.compute(c.first, c.second, c.third, e, t, canSeeMoney = true)
        }.flowOn(Dispatchers.Default).eagerly(null)

    val lastBackupAt: StateFlow<Long> = prefs.lastBackupAt

    private fun str(id: Int): String = getApplication<Application>().getString(id)
    fun say(msg: String) { _messages.tryEmit(msg) }

    /** Call when the app comes to the foreground so "today" is right after midnight. */
    fun refreshToday() { today.value = Dates.today() }

    /** Runs a suspend action and shows a friendly message on failure. */
    fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: IllegalArgumentException) { say(e.message ?: str(R.string.err_generic)) }
            catch (e: Exception) { say(str(R.string.err_generic)) }
        }
    }

    // One financial save at a time: a second tap while a save is running is ignored.
    private val saving = AtomicBoolean(false)
    private fun save(block: suspend () -> Unit) {
        if (!saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            try { block() }
            catch (e: IllegalArgumentException) { say(e.message ?: str(R.string.err_generic)) }
            catch (e: Exception) { say(str(R.string.err_generic)) }
            finally { saving.set(false) }
        }
    }

    // ---------------------------------------------------------------- money actions
    fun addMember(requestId: String, name: String, phone: String?, plan: PlanE?, start: LocalDate, discount: Long, paid: Long, mode: String, done: () -> Unit) = save {
        val p = repo.addMember(requestId, name, phone, plan, start, discount, paid, mode)
        if (p != null) receipt.value = p
        done()
    }

    fun renew(requestId: String, memberId: String, plan: PlanE, start: LocalDate, discount: Long, paid: Long, mode: String, done: () -> Unit) = save {
        val p = repo.renew(requestId, memberId, plan, start, discount, paid, mode)
        if (p != null) receipt.value = p
        done()
    }

    fun collect(requestId: String, memberId: String, membershipId: String?, amount: Long, mode: String, note: String?, done: () -> Unit) = save {
        receipt.value = repo.collect(requestId, memberId, membershipId, amount, mode, note)
        done()
    }

    fun refund(requestId: String, memberId: String, membershipId: String?, amount: Long, mode: String, note: String?, done: () -> Unit) = save {
        receipt.value = repo.refund(requestId, memberId, membershipId, amount, mode, note)
        done()
    }

    fun memberName(id: String): String = members.value.firstOrNull { it.id == id }?.name ?: ""

    fun receiptText(p: PaymentE): String {
        val ms = memberships.value.firstOrNull { it.id == p.membershipId }
        val balance = ms?.let { m -> Ledger.balance(m, payments.value.filter { it.membershipId == m.id }) }
        return Receipts.text(gymName.value, p, memberName(p.memberId), ms?.planName, balance)
    }

    fun balanceOf(ms: MembershipE): Long = Ledger.balance(ms, payments.value.filter { it.membershipId == ms.id })

    // ---------------------------------------------------------------- manual backup / restore (owner-initiated only)
    fun backupTo(uri: Uri) {
        viewModelScope.launch {
            busy.value = true
            try {
                val s = backupMgr.exportTo(uri)
                say(getApplication<Application>().getString(R.string.backup_done, s.members.size, s.payments.size))
            } catch (e: BackupException) { say(e.message ?: str(R.string.backup_failed)) }
            catch (e: Exception) { say(str(R.string.backup_failed)) }
            finally { busy.value = false }
        }
    }

    /** Validates the chosen file and hands it back for the confirmation dialog. */
    fun previewRestore(uri: Uri, onReady: (BackupInfo) -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try { onReady(backupMgr.preview(uri)) }
            catch (e: BackupException) { say(e.message ?: str(R.string.restore_failed)) }
            catch (e: Exception) { say(str(R.string.restore_failed)) }
            finally { busy.value = false }
        }
    }

    fun restore(info: BackupInfo) {
        viewModelScope.launch {
            busy.value = true
            try {
                val r = backupMgr.restore(info)
                SheetsSyncScheduler.requestNow(getApplication())
                say(getApplication<Application>().getString(R.string.restore_done, r.added, r.alreadyPresent))
            } catch (e: Exception) { say(str(R.string.restore_failed)) }
            finally { busy.value = false }
        }
    }

    fun renameGym(name: String) = repo.setGymName(name)
}

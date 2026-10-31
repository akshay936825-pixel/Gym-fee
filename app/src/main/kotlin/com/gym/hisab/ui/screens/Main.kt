@file:OptIn(ExperimentalMaterial3Api::class)

package com.gym.hisab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.gym.hisab.R
import com.gym.hisab.core.Dates
import com.gym.hisab.core.Ids
import com.gym.hisab.core.Ledger
import com.gym.hisab.core.MemberRow
import com.gym.hisab.core.MemberState
import com.gym.hisab.core.Money
import com.gym.hisab.core.Receipts
import com.gym.hisab.data.local.MemberE
import com.gym.hisab.data.local.PlanE
import com.gym.hisab.ui.AppCard
import com.gym.hisab.ui.Avatar
import com.gym.hisab.ui.BarChart
import com.gym.hisab.ui.BigButton
import com.gym.hisab.ui.ChoiceRow
import com.gym.hisab.ui.DateField
import com.gym.hisab.ui.EmptyState
import com.gym.hisab.ui.HisabViewModel
import com.gym.hisab.ui.MoneyInput
import com.gym.hisab.ui.Pill
import com.gym.hisab.ui.ScreenScaffold
import com.gym.hisab.ui.SectionTitle
import com.gym.hisab.ui.Share
import com.gym.hisab.ui.StatTile
import com.gym.hisab.ui.TextInput
import com.gym.hisab.ui.rememberHaptics
import com.gym.hisab.ui.theme.sem
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// ------------------------------------------------------------------------------------ shared bits
@Composable
fun stateLabel(s: MemberState): String = stringResource(
    when (s) {
        MemberState.ACTIVE -> R.string.state_active
        MemberState.EXPIRING -> R.string.state_expiring
        MemberState.EXPIRED -> R.string.state_expired
        MemberState.NO_PLAN -> R.string.state_no_plan
    },
)

@Composable
fun stateColor(s: MemberState): Color = when (s) {
    MemberState.ACTIVE -> sem.good
    MemberState.EXPIRING -> sem.warn
    MemberState.EXPIRED, MemberState.NO_PLAN -> sem.bad
}

@Composable
fun modeChoices(): List<Pair<String, String>> = listOf(
    "cash" to stringResource(R.string.mode_cash), "upi" to stringResource(R.string.mode_upi),
    "card" to stringResource(R.string.mode_card), "other" to stringResource(R.string.mode_other),
)

@Composable
fun MemberItem(r: MemberRow, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(r.member.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.member.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val sub = if (r.endOn != null) stringResource(R.string.valid_till, Dates.pretty(r.endOn)) else stringResource(R.string.no_active_plan)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = sem.muted, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Pill(stateLabel(r.state), stateColor(r.state))
                if (r.balancePaise > 0) {
                    Text(stringResource(R.string.due_amount, Money.format(r.balancePaise)), style = MaterialTheme.typography.bodySmall,
                        color = sem.bad, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ home
@Composable
fun HomeScreen(vm: HisabViewModel, nav: NavHostController) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val gym by vm.gymName.collectAsStateWithLifecycle()
    val writable by vm.writable.collectAsStateWithLifecycle()
    val role by vm.role.collectAsStateWithLifecycle()
    val staff = role == "staff"

    val last7 = remember(payments) {
        val today = Dates.today()
        (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val key = d.toString()
            d to payments.filter { it.paidOn == key }.sumOf { Ledger.signed(it) }.coerceAtLeast(0L)
        }
    }
    val members by vm.members.collectAsStateWithLifecycle()
    val names = remember(members) { members.associate { it.id to it.name } }
    val modes = modeChoices().toMap()
    val recent = remember(payments) { payments.take(5) }
    val dueList = remember(rows) { rows.filter { it.balancePaise > 0 && !it.member.archived }.sortedByDescending { it.balancePaise }.take(5) }
    val renewList = remember(rows) {
        rows.filter { it.state == MemberState.EXPIRING && !it.member.archived }.sortedBy { it.daysLeft ?: 0 }.take(5)
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(gym, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Text(Dates.pretty(Dates.today()), color = sem.muted)
        }
        item {
            BigButton(stringResource(R.string.add_member), onClick = { nav.navigate("addMember") }, enabled = writable)
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.collect_fee), onClick = { nav.navigate("collect/pick") }, secondary = true, enabled = writable)
        }
        stats?.let { s ->
            item {
                StatTile(stringResource(R.string.today_collection), Money.format(s.todayPaise), Modifier.fillMaxWidth(), sem.good)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!staff) {
                        StatTile(stringResource(R.string.month_collection), Money.format(s.monthPaise ?: 0), Modifier.weight(1f))
                        StatTile(stringResource(R.string.expenses), Money.format(s.monthExpensesPaise ?: 0), Modifier.weight(1f), sem.warn)
                    } else {
                        StatTile(stringResource(R.string.pending_fees), Money.format(s.pendingPaise), Modifier.weight(1f), sem.bad)
                        StatTile(stringResource(R.string.active_members), s.activeMembers.toString(), Modifier.weight(1f))
                    }
                }
            }
            if (!staff) item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(stringResource(R.string.pending_fees), Money.format(s.pendingPaise), Modifier.weight(1f), sem.bad)
                    StatTile(stringResource(R.string.active_members), s.activeMembers.toString(), Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppCard(Modifier.weight(1f), onClick = { vm.memberFilter.value = "expiring"; nav.navigate("members") }) {
                        Text(stringResource(R.string.renewals_week), style = MaterialTheme.typography.bodySmall, color = sem.muted)
                        Text(s.upcomingRenewals.toString(), style = MaterialTheme.typography.titleLarge, color = sem.warn)
                    }
                    AppCard(Modifier.weight(1f), onClick = { vm.memberFilter.value = "expired"; nav.navigate("members") }) {
                        Text(stringResource(R.string.expired_members), style = MaterialTheme.typography.bodySmall, color = sem.muted)
                        Text(s.expiredMembers.toString(), style = MaterialTheme.typography.titleLarge, color = sem.bad)
                    }
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.last_7_days), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                BarChart(
                    groups = last7.map { listOf(it.second) },
                    labels = last7.map { it.first.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3) },
                    colors = listOf(MaterialTheme.colorScheme.primary),
                    height = 110,
                )
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.recent_payments), Modifier.padding(top = 4.dp)) }
            items(recent, key = { "p" + it.id }) { p -> PaymentRow(p, names[p.memberId].orEmpty(), modes[p.mode] ?: p.mode) }
        }
        if (dueList.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.pending_fees), Modifier.padding(top = 4.dp)) }
            items(dueList, key = { "d" + it.member.id }) { r -> MemberItem(r) { nav.navigate("member/${r.member.id}") } }
        }
        if (renewList.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.renewals_week), Modifier.padding(top = 4.dp)) }
            items(renewList, key = { "r" + it.member.id }) { r -> MemberItem(r) { nav.navigate("member/${r.member.id}") } }
        }
        if (rows.isEmpty()) item { EmptyState(stringResource(R.string.empty_members)) }
    }
}

// ------------------------------------------------------------------------------------ members list
@Composable
fun MembersScreen(vm: HisabViewModel, nav: NavHostController) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val filter by vm.memberFilter.collectAsStateWithLifecycle()
    val writable by vm.writable.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val shown = remember(rows, query, filter) {
        rows.filter { r ->
            val q = query.trim()
            val matches = q.isEmpty() || r.member.name.contains(q, ignoreCase = true) || (r.member.phone ?: "").contains(q)
            val ok = when (filter) {
                "due" -> r.balancePaise > 0 && !r.member.archived
                "expiring" -> r.state == MemberState.EXPIRING && !r.member.archived
                "expired" -> (r.state == MemberState.EXPIRED || r.state == MemberState.NO_PLAN) && !r.member.archived
                "archived" -> r.member.archived
                else -> !r.member.archived
            }
            matches && ok
        }.let { list -> if (filter == "due") list.sortedByDescending { it.balancePaise } else list }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            TextInput(query, { query = it }, stringResource(R.string.search_members))
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                listOf("all" to stringResource(R.string.filter_all), "due" to stringResource(R.string.filter_due),
                    "expiring" to stringResource(R.string.filter_expiring), "expired" to stringResource(R.string.filter_expired),
                    "archived" to stringResource(R.string.filter_archived)),
                filter, { vm.memberFilter.value = it },
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown, key = { it.member.id }) { r -> MemberItem(r) { nav.navigate("member/${r.member.id}") } }
                if (shown.isEmpty()) item { EmptyState(stringResource(R.string.no_members_found)) }
            }
        }
        if (writable) {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("addMember") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_member)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------------------ add member
@Composable
fun AddMemberScreen(vm: HisabViewModel, nav: NavHostController) {
    val plans by vm.plans.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val writable by vm.writable.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var planId by rememberSaveable { mutableStateOf<String?>(null) }
    var start by remember { mutableStateOf(Dates.today()) }
    var discountText by rememberSaveable { mutableStateOf("") }
    var paidText by rememberSaveable { mutableStateOf("") }
    var paidEdited by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("cash") }
    val requestId = rememberSaveable { Ids.newId() } // one id per form: a double tap can never save twice

    val plan: PlanE? = plans.firstOrNull { it.id == planId } ?: if (planId == null) plans.firstOrNull() else null
    val discount = Money.parseToPaise(discountText) ?: 0L
    val total = ((plan?.pricePaise ?: 0L) - discount).coerceAtLeast(0L)
    val paid: Long? = if (paidEdited) Money.parseToPaise(paidText.ifBlank { "0" }) else total
    val duplicate = phone.length >= 10 && members.any { it.phone == phone.trim() }
    val valid = name.isNotBlank() && writable && paid != null && paid <= total

    ScreenScaffold(stringResource(R.string.add_member), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextInput(name, { name = it }, stringResource(R.string.member_name))
            Spacer(Modifier.height(10.dp))
            TextInput(phone, { phone = it.filter { c -> c.isDigit() }.take(12) }, stringResource(R.string.phone), keyboard = KeyboardType.Phone)
            if (duplicate) Text(stringResource(R.string.duplicate_phone), color = sem.warn, style = MaterialTheme.typography.bodySmall)

            SectionTitle(stringResource(R.string.choose_plan))
            if (plans.isEmpty()) Text(stringResource(R.string.no_plans), color = sem.muted)
            ChoiceRow(
                plans.map { it.id to (it.name + " • " + Money.format(it.pricePaise)) },
                plan?.id ?: "", { planId = it; if (!paidEdited) paidText = "" },
            )
            Spacer(Modifier.height(10.dp))
            DateField(stringResource(R.string.start_date), start, { start = it })
            Spacer(Modifier.height(10.dp))
            MoneyInput(discountText, { discountText = it }, stringResource(R.string.discount_optional))

            SectionTitle(stringResource(R.string.payment_now))
            Text(stringResource(R.string.total_fee, Money.format(total)), color = sem.muted)
            Spacer(Modifier.height(8.dp))
            MoneyInput(
                value = if (paidEdited) paidText else Money.plain(total),
                onChange = { paidEdited = true; paidText = it },
                label = stringResource(R.string.amount_paid),
                error = paid == null || paid > total,
            )
            Spacer(Modifier.height(8.dp))
            ChoiceRow(modeChoices(), mode, { mode = it })
            if (paid != null && paid < total) {
                Text(stringResource(R.string.balance_after, Money.format(total - paid)), color = sem.warn,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(20.dp))
            BigButton(stringResource(R.string.save), enabled = valid, onClick = {
                vm.addMember(requestId, name, phone, plan, start, discount, paid ?: 0L, mode) { nav.popBackStack() }
            })
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------------------------ member detail
@Composable
fun MemberDetailScreen(vm: HisabViewModel, nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val members by vm.members.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val memberships by vm.memberships.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val writable by vm.writable.collectAsStateWithLifecycle()
    val gym by vm.gymName.collectAsStateWithLifecycle()

    val member = members.firstOrNull { it.id == id }
    val row = rows.firstOrNull { it.member.id == id }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var refunding by remember { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(member?.name ?: "", onBack = { nav.popBackStack() }, actions = {
        if (member != null) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; editing = true })
                DropdownMenuItem(
                    text = { Text(stringResource(if (member.archived) R.string.unarchive else R.string.archive)) },
                    onClick = { menu = false; vm.act { vm.repo.updateMember(member.copy(archived = !member.archived)) } },
                )
            }
        }
    }) { pad ->
        if (member == null || row == null) { EmptyState(stringResource(R.string.not_found)); return@ScreenScaffold }
        val myMs = memberships.filter { it.memberId == id }.sortedByDescending { it.startOn }
        val myPay = payments.filter { it.memberId == id }

        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(member.name, 56)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(member.phone ?: stringResource(R.string.no_phone), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.joined_on, Dates.pretty(member.joinedOn)), color = sem.muted, style = MaterialTheme.typography.bodySmall)
                            if (!member.notes.isNullOrBlank()) Text(member.notes, color = sem.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Pill(stateLabel(row.state), stateColor(row.state))
                    }
                    if (row.endOn != null) Text(stringResource(R.string.valid_till, Dates.pretty(row.endOn)), modifier = Modifier.padding(top = 12.dp))
                    if (row.balancePaise > 0) {
                        Text(stringResource(R.string.due_amount, Money.format(row.balancePaise)),
                            style = MaterialTheme.typography.titleLarge, color = sem.bad, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { BigButton(stringResource(R.string.collect_fee), { nav.navigate("collect/$id") }, enabled = writable && row.balancePaise > 0) }
                    Box(Modifier.weight(1f)) { BigButton(stringResource(R.string.renew), { nav.navigate("renew/$id") }, secondary = true, enabled = writable) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        BigButton(stringResource(R.string.remind), secondary = true, onClick = {
                            val text = if (row.balancePaise > 0) Receipts.reminder(gym, member.name, row.balancePaise, row.endOn?.toString())
                            else Receipts.renewalReminder(gym, member.name, row.endOn?.toString() ?: Dates.today().toString())
                            Share.whatsapp(ctx, member.phone, text)
                        })
                    }
                    Box(Modifier.weight(1f)) {
                        BigButton(stringResource(R.string.call), secondary = true, enabled = !member.phone.isNullOrBlank(),
                            onClick = { member.phone?.let { Share.dial(ctx, it) } })
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.memberships)) }
            if (myMs.isEmpty()) item { Text(stringResource(R.string.no_active_plan), color = sem.muted) }
            items(myMs, key = { it.id }) { ms ->
                val bal = vm.balanceOf(ms)
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ms.planName, style = MaterialTheme.typography.titleMedium)
                            Text(Dates.pretty(ms.startOn) + " – " + Dates.pretty(ms.endOn), color = sem.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (ms.status == Ledger.CANCELLED) Pill(stringResource(R.string.cancelled), sem.muted)
                        else if (bal > 0) Pill(stringResource(R.string.due_amount, Money.format(bal)), sem.bad)
                        else Pill(stringResource(R.string.paid), sem.good)
                    }
                    Text(stringResource(R.string.fee_line, Money.format(Ledger.due(ms)), Money.format(Ledger.netPaid(payments.filter { it.membershipId == ms.id }))),
                        color = sem.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    if (vm.canManage && ms.status == Ledger.ACTIVE) {
                        TextButton(onClick = { cancelTarget = ms.id }) { Text(stringResource(R.string.cancel_membership), color = sem.bad) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.payments), Modifier.weight(1f))
                    if (vm.canManage && myPay.any { it.kind == Ledger.PAYMENT }) {
                        TextButton(onClick = { refunding = true }, enabled = writable) { Text(stringResource(R.string.refund)) }
                    }
                }
            }
            if (myPay.isEmpty()) item { Text(stringResource(R.string.no_payments), color = sem.muted) }
            items(myPay, key = { it.id }) { p ->
                AppCard(Modifier.fillMaxWidth(), onClick = { vm.receipt.value = p }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.receiptNo, style = MaterialTheme.typography.bodyMedium)
                            Text(Dates.pretty(p.paidOn) + " • " + p.mode.uppercase(), color = sem.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        val refund = p.kind == Ledger.REFUND
                        Text((if (refund) "-" else "") + Money.format(p.amountPaise), style = MaterialTheme.typography.titleMedium,
                            color = if (refund) sem.bad else sem.good)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        if (editing) EditMemberDialog(member, onDismiss = { editing = false }) { updated -> vm.act { vm.repo.updateMember(updated) }; editing = false }
        if (refunding) {
            RefundDialog(vm, id, myMs.filter { Ledger.netPaid(payments.filter { p -> p.membershipId == it.id }) > 0 }, onDismiss = { refunding = false })
        }
        cancelTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { cancelTarget = null },
                title = { Text(stringResource(R.string.cancel_membership)) },
                text = { Text(stringResource(R.string.cancel_membership_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        myMs.firstOrNull { it.id == target }?.let { ms -> vm.act { vm.repo.cancelMembership(ms) } }
                        cancelTarget = null
                    }) { Text(stringResource(R.string.yes_cancel), color = sem.bad) }
                },
                dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text(stringResource(R.string.no)) } },
            )
        }
    }
}

@Composable
private fun EditMemberDialog(m: MemberE, onDismiss: () -> Unit, onSave: (MemberE) -> Unit) {
    var name by remember { mutableStateOf(m.name) }
    var phone by remember { mutableStateOf(m.phone ?: "") }
    var notes by remember { mutableStateOf(m.notes ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(name, { name = it }, stringResource(R.string.member_name))
                TextInput(phone, { phone = it.filter { c -> c.isDigit() }.take(12) }, stringResource(R.string.phone), keyboard = KeyboardType.Phone)
                TextInput(notes, { notes = it }, stringResource(R.string.notes))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(m.copy(name = name.trim(), phone = phone.trim().ifBlank { null }, notes = notes.trim().ifBlank { null }))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RefundDialog(vm: HisabViewModel, memberId: String, options: List<com.gym.hisab.data.local.MembershipE>, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(options.firstOrNull()?.id) }
    var amount by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("cash") }
    var note by remember { mutableStateOf("") }
    val requestId = rememberSaveable { Ids.newId() }
    val payments by vm.payments.collectAsStateWithLifecycle()
    val paid = selected?.let { sid -> Ledger.netPaid(payments.filter { it.membershipId == sid }) } ?: 0L
    val amt = Money.parseToPaise(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.refund)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceRow(options.map { it.id to it.planName }, selected ?: "", { selected = it })
                Text(stringResource(R.string.refundable, Money.format(paid)), color = sem.muted, style = MaterialTheme.typography.bodySmall)
                MoneyInput(amount, { amount = it }, stringResource(R.string.refund_amount), error = amt != null && amt > paid)
                ChoiceRow(modeChoices(), mode, { mode = it })
                TextInput(note, { note = it }, stringResource(R.string.reason))
            }
        },
        confirmButton = {
            TextButton(enabled = amt != null && amt > 0 && amt <= paid && selected != null, onClick = {
                vm.refund(requestId, memberId, selected, amt ?: 0L, mode, note) { onDismiss() }
            }) { Text(stringResource(R.string.refund)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------------------------ collect fee
@Composable
fun CollectScreen(vm: HisabViewModel, nav: NavHostController, id: String) {
    if (id == "pick") { PickMember(vm, nav); return }
    val members by vm.members.collectAsStateWithLifecycle()
    val memberships by vm.memberships.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val writable by vm.writable.collectAsStateWithLifecycle()
    val member = members.firstOrNull { it.id == id }

    val dues = remember(memberships, payments, id) {
        memberships.filter { it.memberId == id }
            .map { it to Ledger.balance(it, payments.filter { p -> p.membershipId == it.id }) }
            .filter { it.second > 0 }.sortedByDescending { it.second }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var edited by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("cash") }
    var note by rememberSaveable { mutableStateOf("") }
    val requestId = rememberSaveable { Ids.newId() }

    val current = dues.firstOrNull { it.first.id == selected } ?: dues.firstOrNull()
    val balance = current?.second ?: 0L
    val amount: Long? = if (edited) Money.parseToPaise(amountText) else balance
    val valid = writable && current != null && amount != null && amount > 0 && amount <= balance

    ScreenScaffold(stringResource(R.string.collect_fee), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (member != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(member.name)
                    Spacer(Modifier.width(12.dp))
                    Text(member.name, style = MaterialTheme.typography.titleLarge)
                }
            }
            if (current == null) {
                EmptyState(stringResource(R.string.no_dues))
                BigButton(stringResource(R.string.renew), onClick = { nav.navigate("renew/$id") { popUpTo("collect/$id") { inclusive = true } } })
            } else {
                if (dues.size > 1) {
                    SectionTitle(stringResource(R.string.memberships))
                    ChoiceRow(dues.map { it.first.id to (it.first.planName + " • " + Money.format(it.second)) }, current.first.id,
                        { selected = it; edited = false; amountText = "" })
                }
                Text(stringResource(R.string.balance_due, Money.format(balance)), style = MaterialTheme.typography.headlineMedium,
                    color = sem.bad, modifier = Modifier.padding(vertical = 16.dp))
                MoneyInput(
                    value = if (edited) amountText else Money.plain(balance),
                    onChange = { edited = true; amountText = it },
                    label = stringResource(R.string.amount_received),
                    error = amount == null || amount > balance,
                )
                Spacer(Modifier.height(8.dp))
                ChoiceRow(modeChoices(), mode, { mode = it })
                Spacer(Modifier.height(10.dp))
                TextInput(note, { note = it }, stringResource(R.string.note_optional))
                if (amount != null && amount in 1 until balance) {
                    Text(stringResource(R.string.balance_after, Money.format(balance - amount)), color = sem.warn,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(20.dp))
                BigButton(stringResource(R.string.save_and_receipt), enabled = valid, onClick = {
                    vm.collect(requestId, id, current.first.id, amount ?: 0L, mode, note) { nav.popBackStack() }
                })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PickMember(vm: HisabViewModel, nav: NavHostController) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val list = remember(rows, query) {
        rows.filter { !it.member.archived && (query.isBlank() || it.member.name.contains(query, true) || (it.member.phone ?: "").contains(query)) }
            .sortedWith(compareByDescending<MemberRow> { it.balancePaise > 0 }.thenByDescending { it.balancePaise })
    }
    ScreenScaffold(stringResource(R.string.who_is_paying), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            TextInput(query, { query = it }, stringResource(R.string.search_members))
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.member.id }) { r ->
                    MemberItem(r) { nav.navigate("collect/${r.member.id}") { popUpTo("collect/pick") { inclusive = true } } }
                }
                if (list.isEmpty()) item { EmptyState(stringResource(R.string.no_members_found)) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ renew
@Composable
fun RenewScreen(vm: HisabViewModel, nav: NavHostController, id: String) {
    val plans by vm.plans.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val writable by vm.writable.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.member.id == id }
    var planId by rememberSaveable { mutableStateOf<String?>(null) }
    var start by remember(row?.endOn) { mutableStateOf(Ledger.renewalStart(row?.endOn, Dates.today())) }
    var discountText by rememberSaveable { mutableStateOf("") }
    var paidText by rememberSaveable { mutableStateOf("") }
    var paidEdited by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("cash") }
    val requestId = rememberSaveable { Ids.newId() }

    val plan = plans.firstOrNull { it.id == planId } ?: plans.firstOrNull()
    val discount = Money.parseToPaise(discountText) ?: 0L
    val total = ((plan?.pricePaise ?: 0L) - discount).coerceAtLeast(0L)
    val paid: Long? = if (paidEdited) Money.parseToPaise(paidText.ifBlank { "0" }) else total
    val valid = writable && plan != null && paid != null && paid <= total

    ScreenScaffold(stringResource(R.string.renew), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text(row?.member?.name ?: "", style = MaterialTheme.typography.titleLarge)
            SectionTitle(stringResource(R.string.choose_plan))
            ChoiceRow(plans.map { it.id to (it.name + " • " + Money.format(it.pricePaise)) }, plan?.id ?: "", { planId = it; if (!paidEdited) paidText = "" })
            Spacer(Modifier.height(10.dp))
            DateField(stringResource(R.string.start_date), start, { start = it })
            Spacer(Modifier.height(10.dp))
            MoneyInput(discountText, { discountText = it }, stringResource(R.string.discount_optional))
            SectionTitle(stringResource(R.string.payment_now))
            Text(stringResource(R.string.total_fee, Money.format(total)), color = sem.muted)
            Spacer(Modifier.height(8.dp))
            MoneyInput(if (paidEdited) paidText else Money.plain(total), { paidEdited = true; paidText = it },
                stringResource(R.string.amount_paid), error = paid == null || paid > total)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(modeChoices(), mode, { mode = it })
            Spacer(Modifier.height(20.dp))
            BigButton(stringResource(R.string.save), enabled = valid, onClick = {
                plan?.let { vm.renew(requestId, id, it, start, discount, paid ?: 0L, mode) { nav.popBackStack() } }
            })
            Spacer(Modifier.height(24.dp))
        }
    }
}

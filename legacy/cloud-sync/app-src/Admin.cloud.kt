@file:OptIn(ExperimentalMaterial3Api::class)

package com.gym.hisab.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.gym.hisab.BuildConfig
import com.gym.hisab.R
import com.gym.hisab.core.Dates
import com.gym.hisab.core.Ids
import com.gym.hisab.core.Money
import com.gym.hisab.data.StaffRow
import com.gym.hisab.data.SyncUi
import com.gym.hisab.data.local.PlanE
import com.gym.hisab.ui.AppCard
import com.gym.hisab.ui.BigButton
import com.gym.hisab.ui.ChoiceRow
import com.gym.hisab.ui.EmptyState
import com.gym.hisab.ui.HisabViewModel
import com.gym.hisab.ui.MoneyInput
import com.gym.hisab.ui.Pill
import com.gym.hisab.ui.ScreenScaffold
import com.gym.hisab.ui.SectionTitle
import com.gym.hisab.ui.Share
import com.gym.hisab.ui.TextInput
import com.gym.hisab.ui.theme.sem
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------------------------ More
@Composable
private fun MoreRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, color = sem.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MoreScreen(vm: HisabViewModel, nav: NavHostController) {
    val theme by vm.prefs.theme.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val role by vm.role.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    var signOutAsk by remember { mutableStateOf(false) }
    var pwdAsk by remember { mutableStateOf(false) }
    val lang = remember {
        val l = AppCompatDelegate.getApplicationLocales()
        if (!l.isEmpty && l[0]?.language == "hi") "hi" else "en"
    }
    var language by rememberSaveable { mutableStateOf(lang) }

    val syncText = when (val s = sync) {
        is SyncUi.Done -> stringResource(R.string.synced_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.atMillis)))
        SyncUi.Offline -> stringResource(R.string.sync_offline)
        SyncUi.Syncing -> stringResource(R.string.syncing)
        is SyncUi.Error -> s.message
        SyncUi.Idle -> stringResource(R.string.sync_idle)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.tab_more), style = MaterialTheme.typography.headlineMedium) }
        if (vm.canManage) item { MoreRow(stringResource(R.string.plans), stringResource(R.string.plans_sub)) { nav.navigate("plans") } }
        if (vm.isOwner) item { MoreRow(stringResource(R.string.team), stringResource(R.string.team_sub)) { nav.navigate("staff") } }
        if (vm.isOwner) item { MoreRow(stringResource(R.string.sheets), stringResource(R.string.sheets_sub)) { nav.navigate("sheets") } }
        if (vm.canManage) item { MoreRow(stringResource(R.string.ai_assistant), stringResource(R.string.ai_sub)) { nav.navigate("ai") } }
        if (vm.isOwner) item { MoreRow(stringResource(R.string.subscription), stringResource(R.string.subscription_sub)) { nav.navigate("subscription") } }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(listOf("en" to "English", "hi" to "हिन्दी"), language, {
                    language = it
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
                })
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light),
                    "dark" to stringResource(R.string.theme_dark)), theme, { vm.prefs.setTheme(it) })
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth(), onClick = { vm.sync() }) {
                Text(stringResource(R.string.sync_now), style = MaterialTheme.typography.titleMedium)
                Text(syncText + if (pending > 0) " • " + stringResource(R.string.waiting_n, pending) else "", color = sem.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!demo) item { MoreRow(stringResource(R.string.change_password)) { pwdAsk = true } }
        item {
            val roleLabel = when (role) { "owner" -> R.string.role_owner; "manager" -> R.string.role_manager; else -> R.string.role_staff }
            Text(stringResource(R.string.signed_in_as, vm.repo.email ?: stringResource(roleLabel)) + " • " + stringResource(roleLabel),
                color = sem.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            BigButton(stringResource(R.string.sign_out), secondary = true, onClick = { signOutAsk = true })
            Text("GYM HISAB ${BuildConfig.VERSION_NAME}", color = sem.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
    }

    if (signOutAsk) {
        AlertDialog(
            onDismissRequest = { signOutAsk = false },
            title = { Text(stringResource(R.string.sign_out)) },
            text = { Text(if (pending > 0 && !demo) stringResource(R.string.sign_out_pending, pending) else stringResource(R.string.sign_out_body)) },
            confirmButton = { TextButton(onClick = { signOutAsk = false; vm.signOut() }) { Text(stringResource(R.string.sign_out), color = sem.bad) } },
            dismissButton = { TextButton(onClick = { signOutAsk = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (pwdAsk) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pwdAsk = false },
            title = { Text(stringResource(R.string.change_password)) },
            text = { TextInput(pw, { pw = it }, stringResource(R.string.new_password)) },
            confirmButton = {
                TextButton(enabled = pw.length >= 8, onClick = { vm.act { vm.repo.changePassword(pw); vm.say("OK") }; pwdAsk = false }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { pwdAsk = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ------------------------------------------------------------------------------------ Plans
@Composable
fun PlansScreen(vm: HisabViewModel, nav: NavHostController) {
    val plans by vm.plans.collectAsStateWithLifecycle()
    val writable by vm.writable.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PlanE?>(null) }
    var creating by remember { mutableStateOf(false) }

    ScreenScaffold(stringResource(R.string.plans), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            BigButton(stringResource(R.string.add_plan), onClick = { creating = true }, enabled = writable)
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(plans, key = { it.id }) { p ->
                    AppCard(Modifier.fillMaxWidth(), onClick = { editing = p }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.months_n, p.durationMonths), color = sem.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(Money.format(p.pricePaise), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (plans.isEmpty()) item { EmptyState(stringResource(R.string.no_plans)) }
            }
        }
    }
    if (creating || editing != null) {
        PlanDialog(editing, onDismiss = { creating = false; editing = null }) { saved ->
            vm.act { vm.repo.savePlan(saved) }
            creating = false; editing = null
        }
    }
}

@Composable
private fun PlanDialog(existing: PlanE?, onDismiss: () -> Unit, onSave: (PlanE) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var months by remember { mutableStateOf((existing?.durationMonths ?: 1).toString()) }
    var price by remember { mutableStateOf(existing?.let { Money.plain(it.pricePaise) } ?: "") }
    val p = Money.parseToPaise(price)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.add_plan else R.string.edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(name, { name = it }, stringResource(R.string.plan_name))
                ChoiceRow(listOf("1" to ctx.getString(R.string.months_n, 1), "3" to ctx.getString(R.string.months_n, 3),
                    "6" to ctx.getString(R.string.months_n, 6), "12" to ctx.getString(R.string.months_n, 12)), months, { months = it })
                MoneyInput(price, { price = it }, stringResource(R.string.price))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && p != null, onClick = {
                val base = existing ?: PlanE(Ids.newId(), "", name, 1, 0)
                onSave(base.copy(name = name.trim(), durationMonths = months.toInt(), pricePaise = p ?: 0L, gymId = base.gymId))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { onSave(existing.copy(active = false)) }) { Text(stringResource(R.string.archive), color = sem.bad) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

// ------------------------------------------------------------------------------------ Staff
@Composable
fun StaffScreen(vm: HisabViewModel, nav: NavHostController) {
    val list = remember { mutableStateListOf<StaffRow>() }
    var adding by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { try { list.clear(); list.addAll(vm.repo.staff()) } catch (e: Exception) { vm.say(e.message ?: "Error") } }

    ScreenScaffold(stringResource(R.string.team), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            BigButton(stringResource(R.string.add_staff), onClick = { adding = true })
            Text(stringResource(R.string.team_help), color = sem.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.userId }) { s ->
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.displayName.ifBlank { s.email ?: "" }, style = MaterialTheme.typography.titleMedium)
                                Text((s.email ?: "") , color = sem.muted, style = MaterialTheme.typography.bodySmall)
                                Pill(stringResource(when (s.role) { "owner" -> R.string.role_owner; "manager" -> R.string.role_manager; else -> R.string.role_staff }), sem.muted)
                            }
                            if (s.role != "owner") {
                                Switch(checked = s.active, onCheckedChange = { on ->
                                    vm.act { vm.repo.setStaffActive(s.userId, on); reload++ }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var pw by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("staff") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.add_staff)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(name, { name = it }, stringResource(R.string.your_name))
                    TextInput(email, { email = it.trim() }, stringResource(R.string.email), keyboard = KeyboardType.Email)
                    TextInput(pw, { pw = it }, stringResource(R.string.temp_password))
                    ChoiceRow(listOf("staff" to stringResource(R.string.role_staff), "manager" to stringResource(R.string.role_manager)), role, { role = it })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && email.contains("@") && pw.length >= 8, onClick = {
                    vm.act { vm.repo.createStaff(name.trim(), email, pw, role); reload++ }
                    adding = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ------------------------------------------------------------------------------------ Google Sheets (optional)
@Composable
fun SheetsScreen(vm: HisabViewModel, nav: NavHostController) {
    var email by remember { mutableStateOf<String?>(null) }
    var link by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val busy = remember { mutableStateOf(false) }
    val demo by vm.demo.collectAsStateWithLifecycle()
    val okText = stringResource(R.string.sheets_done)
    LaunchedEffect(Unit) { try { email = vm.repo.sheetsEmail() } catch (e: Exception) { status = e.message } }

    ScreenScaffold(stringResource(R.string.sheets), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.sheets_optional), color = sem.muted)
            SectionTitle(stringResource(R.string.sheets_steps_title))
            Text(stringResource(R.string.sheets_steps))
            if (!email.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.share_with_email), style = MaterialTheme.typography.bodySmall, color = sem.muted)
                    Text(email ?: "", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            TextInput(link, { link = it.trim() }, stringResource(R.string.sheet_link))
            Spacer(Modifier.height(12.dp))
            BigButton(stringResource(R.string.connect_sync), enabled = link.isNotBlank() && !busy.value && !demo, onClick = {
                busy.value = true
                vm.act { try { vm.repo.sheetsConnect(link); vm.say(okText) } finally { busy.value = false } }
            })
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.sync_sheet_now), secondary = true, enabled = !busy.value && !demo, onClick = {
                busy.value = true
                vm.act { try { vm.repo.sheetsSync(); vm.say(okText) } finally { busy.value = false } }
            })
            status?.let { Text(it, color = sem.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

// ------------------------------------------------------------------------------------ AI assistant (optional)
@Composable
fun AiScreen(vm: HisabViewModel, nav: NavHostController) {
    val chat = remember { mutableStateListOf<Pair<String, String>>() }
    var q by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val demo by vm.demo.collectAsStateWithLifecycle()
    val suggestions = listOf(R.string.ai_q1, R.string.ai_q2, R.string.ai_q3).map { stringResource(it) }

    fun ask(text: String) {
        if (text.isBlank() || busy) return
        busy = true; q = ""
        vm.act {
            try { chat.add(text to vm.repo.askAi(text)) } finally { busy = false }
        }
    }

    ScreenScaffold(stringResource(R.string.ai_assistant), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Text(stringResource(if (demo) R.string.ai_demo else R.string.ai_note), color = sem.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(chat.size) { i ->
                    val (qq, aa) = chat[i]
                    AppCard(Modifier.fillMaxWidth()) {
                        Text(qq, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(aa)
                    }
                }
                if (chat.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { s -> BigButton(s, secondary = true, onClick = { ask(s) }, enabled = !demo) }
                    }
                }
                if (busy) item { Text(stringResource(R.string.thinking), color = sem.muted) }
            }
            Spacer(Modifier.height(8.dp))
            TextInput(q, { q = it }, stringResource(R.string.ask_question))
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.ask), onClick = { ask(q) }, enabled = q.isNotBlank() && !busy && !demo)
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ------------------------------------------------------------------------------------ Subscription
@Composable
fun SubscriptionScreen(vm: HisabViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val status by vm.prefs.subStatus.collectAsStateWithLifecycle()
    val until by vm.prefs.subUntil.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    val label = when (status) {
        "active" -> R.string.sub_active
        "trialing" -> R.string.sub_trial
        "past_due" -> R.string.sub_past_due
        else -> R.string.sub_expired
    }
    val good = status == "active" || status == "trialing"

    ScreenScaffold(stringResource(R.string.subscription), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            AppCard(Modifier.fillMaxWidth()) {
                Pill(stringResource(label), if (good) sem.good else sem.bad)
                until?.takeIf { it.length >= 10 }?.let {
                    Text(stringResource(R.string.valid_until, Dates.pretty(it.take(10))), modifier = Modifier.padding(top = 10.dp))
                }
            }
            Text(stringResource(R.string.sub_server_note), color = sem.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
            BigButton(stringResource(R.string.pay_monthly), enabled = !demo, onClick = { vm.act { Share.openUrl(ctx, vm.repo.checkoutUrl("monthly")) } })
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.pay_yearly), secondary = true, enabled = !demo, onClick = { vm.act { Share.openUrl(ctx, vm.repo.checkoutUrl("yearly")) } })
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.refresh_status), secondary = true, enabled = !demo, onClick = { vm.act { vm.repo.refreshSubscription() } })
        }
    }
}

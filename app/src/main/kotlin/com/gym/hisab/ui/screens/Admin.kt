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
    val gym by vm.gymName.collectAsStateWithLifecycle()
    val lastBackup by vm.lastBackupAt.collectAsStateWithLifecycle()
    var renameAsk by remember { mutableStateOf(false) }
    val lang = remember {
        val l = AppCompatDelegate.getApplicationLocales()
        if (!l.isEmpty && l[0]?.language == "hi") "hi" else "en"
    }
    var language by rememberSaveable { mutableStateOf(lang) }

    val backupSub = if (lastBackup <= 0L) stringResource(R.string.backup_never)
    else stringResource(R.string.backup_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastBackup)))

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.tab_more), style = MaterialTheme.typography.headlineMedium) }
        item { MoreRow(stringResource(R.string.backup_title), backupSub) { nav.navigate("backup") } }
        item { MoreRow(stringResource(R.string.plans), stringResource(R.string.plans_sub)) { nav.navigate("plans") } }
        item { MoreRow(stringResource(R.string.gym_name), gym) { renameAsk = true } }
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
            Text(stringResource(R.string.local_only_note), color = sem.muted, style = MaterialTheme.typography.bodySmall)
            Text("GYM HISAB ${BuildConfig.VERSION_NAME}", color = sem.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
    }

    if (renameAsk) {
        var name by remember { mutableStateOf(gym) }
        AlertDialog(
            onDismissRequest = { renameAsk = false },
            title = { Text(stringResource(R.string.gym_name)) },
            text = { TextInput(name, { name = it }, stringResource(R.string.gym_name)) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.renameGym(name); renameAsk = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renameAsk = false }) { Text(stringResource(R.string.cancel)) } },
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


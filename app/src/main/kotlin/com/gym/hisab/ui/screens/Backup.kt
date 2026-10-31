@file:OptIn(ExperimentalMaterial3Api::class)

package com.gym.hisab.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.gym.hisab.R
import com.gym.hisab.core.Dates
import com.gym.hisab.core.Exports
import com.gym.hisab.data.backup.BackupInfo
import com.gym.hisab.ui.AppCard
import com.gym.hisab.ui.BigButton
import com.gym.hisab.ui.HisabViewModel
import com.gym.hisab.ui.ScreenScaffold
import com.gym.hisab.ui.SectionTitle
import com.gym.hisab.ui.Share
import com.gym.hisab.ui.theme.sem
import java.text.DateFormat
import java.util.Date

/**
 * Manual backup / restore / export. Everything here is started by the owner pressing a button.
 * Nothing runs at startup and nothing uses the network.
 */
@Composable
fun BackupScreen(vm: HisabViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val last by vm.lastBackupAt.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<BackupInfo?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.backupTo(uri)
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.previewRestore(uri) { pending = it }
    }

    ScreenScaffold(stringResource(R.string.backup_title), onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            AppCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.backup_warning_title), style = MaterialTheme.typography.titleMedium, color = sem.warn)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.backup_warning_body))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (last <= 0L) stringResource(R.string.backup_never)
                    else stringResource(R.string.backup_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))),
                    color = sem.muted, style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionTitle(stringResource(R.string.backup_now_title))
            Text(stringResource(R.string.backup_now_body), color = sem.muted)
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.backup_now), enabled = !busy, onClick = {
                saveLauncher.launch("gym-hisab-backup-${Dates.today()}.json")
            })

            SectionTitle(stringResource(R.string.restore_title))
            Text(stringResource(R.string.restore_body), color = sem.muted)
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.restore_pick), secondary = true, enabled = !busy, onClick = {
                openLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
            })

            SectionTitle(stringResource(R.string.sheets_export_title))
            Text(stringResource(R.string.sheets_export_body), color = sem.muted)
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.export_members), secondary = true, enabled = !busy, onClick = {
                Share.csv(ctx, "members.csv", Exports.members(vm.rows.value))
            })
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.export_payments), secondary = true, enabled = !busy, onClick = {
                Share.csv(ctx, "payments.csv", Exports.payments(vm.payments.value) { vm.memberName(it) })
            })
            Spacer(Modifier.height(24.dp))
        }
    }

    pending?.let { info ->
        val s = info.snapshot
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                Text(stringResource(R.string.restore_confirm_body, s.members.size, s.payments.size, s.memberships.size, info.createdAt.take(10)))
            },
            confirmButton = { TextButton(onClick = { vm.restore(info); pending = null }) { Text(stringResource(R.string.restore_go)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

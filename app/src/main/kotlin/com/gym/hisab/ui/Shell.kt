@file:OptIn(ExperimentalMaterial3Api::class)

package com.gym.hisab.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gym.hisab.R
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.ui.screens.AddMemberScreen
import com.gym.hisab.ui.screens.BackupScreen
import com.gym.hisab.ui.screens.CollectScreen
import com.gym.hisab.ui.screens.HomeScreen
import com.gym.hisab.ui.screens.MemberDetailScreen
import com.gym.hisab.ui.screens.MembersScreen
import com.gym.hisab.ui.screens.MoneyScreen
import com.gym.hisab.ui.screens.MoreScreen
import com.gym.hisab.ui.screens.PlansScreen
import com.gym.hisab.ui.screens.RenewScreen
import kotlinx.coroutines.flow.first

/** Opens straight into the app. No sign-in, no loading screen, nothing waits for a network. */
@Composable
fun HisabRoot(vm: HisabViewModel) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            MainShell(vm)
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp))
        }
    }
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

@Composable
fun MainShell(vm: HisabViewModel) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val receipt by vm.receipt.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    val tabs = remember {
        listOf(
            Tab("home", R.string.tab_home, Icons.Filled.Home), Tab("members", R.string.tab_members, Icons.Filled.People),
            Tab("money", R.string.tab_money, Icons.Filled.Payments), Tab("more", R.string.tab_more, Icons.Filled.MoreHoriz),
        )
    }

    // Tell the system when the first real data frame is ready (used by startup measurements).
    LaunchedEffect(Unit) { vm.stats.first { it != null }; activity?.reportFullyDrawn() }

    // Keep "today" correct when the app returns to the foreground (e.g. the next morning).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.refreshToday() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (route in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                composable("home") { HomeScreen(vm, nav) }
                composable("members") { MembersScreen(vm, nav) }
                composable("money") { MoneyScreen(vm) }
                composable("more") { MoreScreen(vm, nav) }
                composable("addMember") { AddMemberScreen(vm, nav) }
                composable("member/{id}") { MemberDetailScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
                composable("collect/{id}") { CollectScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
                composable("renew/{id}") { RenewScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
                composable("plans") { PlansScreen(vm, nav) }
                composable("backup") { BackupScreen(vm, nav) }
            }
        }
    }
    receipt?.let { ReceiptDialog(vm, it) { vm.receipt.value = null } }
}

// ------------------------------------------------------------------------------------ receipt
@Composable
fun ReceiptDialog(vm: HisabViewModel, p: PaymentE, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val hap = rememberHaptics()
    val text = remember(p.id) { vm.receiptText(p) }
    val phone = vm.members.value.firstOrNull { it.id == p.memberId }?.phone
    LaunchedEffect(p.id) { hap.success() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.receipt_saved)) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start) },
        confirmButton = { TextButton(onClick = { Share.whatsapp(ctx, phone, text) }) { Text(stringResource(R.string.send_whatsapp)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { Share.text(ctx, text) }) { Text(stringResource(R.string.share)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.done)) }
            }
        },
    )
}

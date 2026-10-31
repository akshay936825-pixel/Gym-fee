@file:OptIn(ExperimentalMaterial3Api::class)

package com.gym.hisab.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gym.hisab.R
import com.gym.hisab.data.SyncUi
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.ui.screens.AddMemberScreen
import com.gym.hisab.ui.screens.AiScreen
import com.gym.hisab.ui.screens.AttendanceScreen
import com.gym.hisab.ui.screens.CollectScreen
import com.gym.hisab.ui.screens.HomeScreen
import com.gym.hisab.ui.screens.MemberDetailScreen
import com.gym.hisab.ui.screens.MembersScreen
import com.gym.hisab.ui.screens.MoneyScreen
import com.gym.hisab.ui.screens.MoreScreen
import com.gym.hisab.ui.screens.PlansScreen
import com.gym.hisab.ui.screens.RenewScreen
import com.gym.hisab.ui.screens.SheetsScreen
import com.gym.hisab.ui.screens.StaffScreen
import com.gym.hisab.ui.screens.SubscriptionScreen
import com.gym.hisab.ui.theme.sem
import kotlinx.coroutines.delay

@Composable
fun HisabRoot(vm: HisabViewModel) {
    val auth by vm.auth.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (auth) {
                Auth.LOADING -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                Auth.SIGNED_OUT -> AuthScreen(vm)
                Auth.NEEDS_GYM -> GymSetupScreen(vm)
                Auth.READY -> MainShell(vm)
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp))
        }
    }
}

// ------------------------------------------------------------------------------------ sign in / sign up
@Composable
fun AuthScreen(vm: HisabViewModel) {
    var signup by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val hap = rememberHaptics()
    val valid = email.contains("@") && password.length >= 8 && (!signup || name.isNotBlank())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.tagline), color = sem.muted, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp))
        if (signup) {
            TextInput(name, { name = it }, stringResource(R.string.your_name))
            Spacer(Modifier.height(12.dp))
        }
        TextInput(email, { email = it.trim() }, stringResource(R.string.email), keyboard = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.OutlinedTextField(
            value = password, onValueChange = { password = it }, singleLine = true,
            label = { Text(stringResource(R.string.password_min)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(20.dp))
        BigButton(
            if (busy) stringResource(R.string.please_wait) else stringResource(if (signup) R.string.create_account else R.string.sign_in),
            onClick = { if (signup) vm.signUp(email, password, name) else vm.signIn(email, password) },
            enabled = valid && !busy && vm.repo.backendConfigured,
        )
        TextButton(onClick = { hap.tick(); signup = !signup }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(if (signup) R.string.have_account else R.string.new_here))
        }
        if (!vm.repo.backendConfigured) {
            Spacer(Modifier.height(16.dp))
            AppCard {
                Text(stringResource(R.string.demo_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.demo_body), color = sem.muted, modifier = Modifier.padding(vertical = 8.dp))
                BigButton(stringResource(R.string.try_demo), onClick = { vm.startDemo() }, secondary = true)
            }
        }
    }
}

@Composable
fun GymSetupScreen(vm: HisabViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding()) {
        Spacer(Modifier.height(48.dp))
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.setup_body), color = sem.muted, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
        TextInput(name, { name = it }, stringResource(R.string.gym_name))
        Spacer(Modifier.height(12.dp))
        TextInput(phone, { phone = it }, stringResource(R.string.gym_phone), keyboard = KeyboardType.Phone)
        Spacer(Modifier.height(12.dp))
        TextInput(city, { city = it }, stringResource(R.string.city))
        Spacer(Modifier.height(20.dp))
        BigButton(stringResource(R.string.create_gym), onClick = { vm.createGym(name, phone, city) }, enabled = name.trim().length >= 2 && !busy)
        TextButton(onClick = { vm.signOut() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.sign_out)) }
    }
}

// ------------------------------------------------------------------------------------ main shell
private data class Tab(val route: String, val label: Int, val icon: ImageVector)

@Composable
fun MainShell(vm: HisabViewModel) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val role by vm.role.collectAsStateWithLifecycle()
    val receipt by vm.receipt.collectAsStateWithLifecycle()
    val tabs = if (role == "staff") listOf(
        Tab("home", R.string.tab_home, Icons.Filled.Home), Tab("members", R.string.tab_members, Icons.Filled.People),
        Tab("attendance", R.string.tab_checkin, Icons.Filled.HowToReg), Tab("more", R.string.tab_more, Icons.Filled.MoreHoriz),
    ) else listOf(
        Tab("home", R.string.tab_home, Icons.Filled.Home), Tab("members", R.string.tab_members, Icons.Filled.People),
        Tab("money", R.string.tab_money, Icons.Filled.Payments), Tab("more", R.string.tab_more, Icons.Filled.MoreHoriz),
    )

    // Ask once for permission to show fee/renewal reminders (Android 13+).
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // While the app is open, quietly pull other phones' changes every minute.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { vm.sync(); delay(60_000) } }
    }

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
            StatusBanner(vm) { nav.navigate("subscription") }
            NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                composable("home") { HomeScreen(vm, nav) }
                composable("members") { MembersScreen(vm, nav) }
                composable("money") { MoneyScreen(vm) }
                composable("more") { MoreScreen(vm, nav) }
                composable("attendance") { AttendanceScreen(vm, nav) }
                composable("addMember") { AddMemberScreen(vm, nav) }
                composable("member/{id}") { MemberDetailScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
                composable("collect/{id}") { CollectScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
                composable("renew/{id}") { RenewScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
                composable("plans") { PlansScreen(vm, nav) }
                composable("staff") { StaffScreen(vm, nav) }
                composable("sheets") { SheetsScreen(vm, nav) }
                composable("ai") { AiScreen(vm, nav) }
                composable("subscription") { SubscriptionScreen(vm, nav) }
            }
        }
    }
    receipt?.let { ReceiptDialog(vm, it) { vm.receipt.value = null } }
}

@Composable
private fun StatusBanner(vm: HisabViewModel, onSubscribe: () -> Unit) {
    val demo by vm.demo.collectAsStateWithLifecycle()
    val writable by vm.writable.collectAsStateWithLifecycle()
    val failed by vm.failedCount.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val role by vm.role.collectAsStateWithLifecycle()

    @Composable
    fun bar(text: String, color: androidx.compose.ui.graphics.Color, action: String? = null, onAction: () -> Unit = {}) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(text, color)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }

    when {
        demo -> bar(stringResource(R.string.banner_demo), sem.warn)
        !writable -> bar(
            stringResource(R.string.banner_expired), sem.bad,
            if (role == "owner") stringResource(R.string.renew_now) else null, onSubscribe,
        )
        failed > 0 -> bar(stringResource(R.string.banner_failed, failed), sem.bad, stringResource(R.string.retry)) { vm.retryFailed() }
        sync is SyncUi.Offline && pending > 0 -> bar(stringResource(R.string.banner_waiting, pending), sem.warn)
    }
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

@file:OptIn(ExperimentalMaterial3Api::class)

package com.gym.hisab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gym.hisab.R
import com.gym.hisab.core.Dates
import com.gym.hisab.core.Exports
import com.gym.hisab.core.Money
import com.gym.hisab.core.Reports
import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.ui.AppCard
import com.gym.hisab.ui.BarChart
import com.gym.hisab.ui.BigButton
import com.gym.hisab.ui.ChoiceRow
import com.gym.hisab.ui.DateField
import com.gym.hisab.ui.EmptyState
import com.gym.hisab.ui.HisabViewModel
import com.gym.hisab.ui.MoneyInput
import com.gym.hisab.ui.SectionTitle
import com.gym.hisab.ui.Share
import com.gym.hisab.ui.StatTile
import com.gym.hisab.ui.TextInput
import com.gym.hisab.ui.theme.sem
import java.time.YearMonth

private val CATEGORIES = listOf("Rent", "Electricity", "Salary", "Equipment", "Maintenance", "Other")

@Composable
fun MoneyScreen(vm: HisabViewModel) {
    var tab by rememberSaveable { mutableStateOf("payments") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        ChoiceRow(listOf("payments" to stringResource(R.string.tab_payments), "reports" to stringResource(R.string.reports), "expenses" to stringResource(R.string.expenses)), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        when (tab) { "payments" -> PaymentsTab(vm); "reports" -> ReportsTab(vm); else -> ExpensesTab(vm) }
    }
}

@Composable
private fun ReportsTab(vm: HisabViewModel) {
    val ctx = LocalContext.current
    val payments by vm.payments.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val thisMonth = YearMonth.from(vm.today.collectAsStateWithLifecycle().value)
    val points = remember(payments, expenses, thisMonth) { Reports.monthly(payments, expenses, thisMonth, 6) }
    // Store the month itself, not an index: the visible options are newest-first while
    // Reports.monthly is oldest-first, and indices used to select the wrong month.
    var selectedMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val p = points.firstOrNull { it.month.toString() == selectedMonth } ?: points.last()
    val byCat = remember(expenses, p.month) { Reports.expenseByCategory(expenses, p.month) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.income_vs_expense), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                BarChart(
                    groups = points.map { listOf((it.collected - it.refunded).coerceAtLeast(0L), it.expenses) },
                    labels = points.map { Dates.monthLabel(it.month).substringBefore(' ') },
                    colors = listOf(MaterialTheme.colorScheme.primary, sem.warn),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("● " + stringResource(R.string.income), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Text("● " + stringResource(R.string.expenses), color = sem.warn, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            ChoiceRow(points.map { it.month.toString() to Dates.monthLabel(it.month) }.reversed(),
                p.month.toString(), { selectedMonth = it })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(stringResource(R.string.collected), Money.format(p.collected), Modifier.weight(1f), sem.good)
                StatTile(stringResource(R.string.refunds), Money.format(p.refunded), Modifier.weight(1f), sem.bad)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(stringResource(R.string.expenses), Money.format(p.expenses), Modifier.weight(1f), sem.warn)
                StatTile(stringResource(R.string.profit), Money.format(p.net), Modifier.weight(1f), if (p.net >= 0) sem.good else sem.bad)
            }
        }
        if (byCat.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.expense_by_category)) }
            items(byCat, key = { it.first }) { (cat, total) ->
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cat, Modifier.weight(1f))
                        Text(Money.format(total), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.export_csv)) }
        item {
            BigButton(stringResource(R.string.export_members), secondary = true, onClick = {
                Share.csv(ctx, "members.csv", Exports.members(rows))
            })
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.export_payments), secondary = true, onClick = {
                Share.csv(ctx, "payments.csv", Exports.payments(payments) { vm.memberName(it) })
            })
            Spacer(Modifier.height(8.dp))
            BigButton(stringResource(R.string.export_expenses), secondary = true, onClick = {
                Share.csv(ctx, "expenses.csv", Exports.expenses(expenses))
            })
        }
    }
}

@Composable
private fun ExpensesTab(vm: HisabViewModel) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val writable by vm.writable.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ExpenseE?>(null) }

    Column(Modifier.fillMaxSize()) {
        BigButton(stringResource(R.string.add_expense), onClick = { adding = true }, enabled = writable)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(expenses, key = { it.id }) { e ->
                AppCard(Modifier.fillMaxWidth(), onClick = { deleting = e }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.category, style = MaterialTheme.typography.titleMedium)
                            Text(Dates.pretty(e.spentOn) + (e.note?.let { " • $it" } ?: ""), color = sem.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(Money.format(e.amountPaise), style = MaterialTheme.typography.titleMedium, color = sem.warn)
                    }
                }
            }
            if (expenses.isEmpty()) item { EmptyState(stringResource(R.string.no_expenses)) }
        }
    }

    if (adding) AddExpenseDialog(onDismiss = { adding = false }) { cat, amt, date, note ->
        vm.act { vm.repo.addExpense(cat, amt, date, note) }
        adding = false
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_expense)) },
            text = { Text(e.category + " • " + Money.format(e.amountPaise)) },
            confirmButton = { TextButton(onClick = { vm.act { vm.repo.deleteExpense(e) }; deleting = null }) { Text(stringResource(R.string.delete), color = sem.bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AddExpenseDialog(onDismiss: () -> Unit, onSave: (String, Long, java.time.LocalDate, String?) -> Unit) {
    var cat by remember { mutableStateOf(CATEGORIES.first()) }
    var custom by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Dates.today()) }
    var note by remember { mutableStateOf("") }
    val amt = Money.parseToPaise(amount)
    val category = if (cat == "Other" && custom.isNotBlank()) custom.trim() else cat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_expense)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceRow(CATEGORIES.map { it to it }, cat, { cat = it })
                if (cat == "Other") TextInput(custom, { custom = it }, stringResource(R.string.category_name))
                MoneyInput(amount, { amount = it }, stringResource(R.string.amount))
                DateField(stringResource(R.string.date), date, { date = it })
                TextInput(note, { note = it }, stringResource(R.string.note_optional))
            }
        },
        confirmButton = {
            TextButton(enabled = amt != null && amt > 0, onClick = { onSave(category, amt ?: 0L, date, note.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

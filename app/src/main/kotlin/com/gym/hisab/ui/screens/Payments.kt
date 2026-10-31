package com.gym.hisab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gym.hisab.R
import com.gym.hisab.core.Dates
import com.gym.hisab.core.Ledger
import com.gym.hisab.core.Money
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.ui.AppCard
import com.gym.hisab.ui.HisabViewModel
import com.gym.hisab.ui.StatTile
import com.gym.hisab.ui.theme.sem

/** One payment or refund line. Refunds are shown negative and in red; nothing is ever hidden. */
@Composable
fun PaymentRow(p: PaymentE, memberName: String, modeLabel: String) {
    val refund = p.kind == Ledger.REFUND
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(memberName.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val sub = modeLabel + " • " + Dates.pretty(p.paidOn) + if (refund) " • " + stringResource(R.string.refund) else ""
                Text(sub, color = sem.muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                (if (refund) "-" else "") + Money.format(p.amountPaise),
                style = MaterialTheme.typography.titleMedium,
                color = if (refund) sem.bad else sem.good,
            )
        }
    }
}

/** Collection / Hisab: every payment, newest first, grouped by day with the day's total. */
@Composable
fun PaymentsTab(vm: HisabViewModel) {
    val payments by vm.payments.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val names = remember(members) { members.associate { it.id to it.name } }
    val modes = modeChoices().toMap()
    val days = remember(payments) {
        payments.groupBy { it.paidOn }.entries.map { e -> Triple(e.key, e.value, e.value.sumOf { Ledger.signed(it) }) }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        stats?.let { s ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(stringResource(R.string.today_collection), Money.format(s.todayPaise), Modifier.weight(1f), sem.good)
                    StatTile(stringResource(R.string.month_collection), Money.format(s.monthPaise ?: 0), Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        for ((day, list, total) in days) {
            item(key = "day$day") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Dates.pretty(day), Modifier.weight(1f), color = sem.muted, style = MaterialTheme.typography.titleSmall)
                    Text(Money.format(total), style = MaterialTheme.typography.titleSmall)
                }
            }
            items(list, key = { it.id }) { p -> PaymentRow(p, names[p.memberId].orEmpty(), modes[p.mode] ?: p.mode) }
        }
        if (days.isEmpty()) item { com.gym.hisab.ui.EmptyState(stringResource(R.string.no_payments_yet)) }
    }
}

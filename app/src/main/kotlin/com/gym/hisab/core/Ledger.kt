package com.gym.hisab.core

import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.data.local.MemberE
import com.gym.hisab.data.local.MembershipE
import com.gym.hisab.data.local.PaymentE
import java.time.LocalDate
import java.time.YearMonth

/**
 * Pure financial logic. No Android, no I/O - so it is fully unit-tested.
 * The formulas are deliberately identical to the SQL in supabase/migrations/..._reports.sql.
 */
object Ledger {
    const val PAYMENT = "payment"
    const val REFUND = "refund"
    const val ACTIVE = "active"
    const val CANCELLED = "cancelled"

    fun signed(p: PaymentE): Long = if (p.kind == REFUND) -p.amountPaise else p.amountPaise
    fun netPaid(payments: List<PaymentE>): Long = payments.sumOf { signed(it) }
    fun due(ms: MembershipE): Long = ms.pricePaise - ms.discountPaise

    /** What the member still owes for this membership. Cancelled memberships owe nothing. */
    fun balance(ms: MembershipE, payments: List<PaymentE>): Long =
        if (ms.status == CANCELLED) 0L else due(ms) - netPaid(payments)

    /** 1 month from 15 Jan -> ends 14 Feb. Handles month ends (31 Jan + 1 month -> 27 Feb). */
    fun endDate(start: LocalDate, months: Int): LocalDate = start.plusMonths(months.toLong()).minusDays(1)

    /** Renewing before expiry continues from the old end date; renewing after expiry starts today. */
    fun renewalStart(previousEnd: LocalDate?, today: LocalDate): LocalDate =
        if (previousEnd != null && !previousEnd.isBefore(today)) previousEnd.plusDays(1) else today
}

data class DashboardStats(
    val todayPaise: Long,
    val monthPaise: Long?,          // null when the role may not see money totals
    val monthExpensesPaise: Long?,
    val pendingPaise: Long,
    val pendingMembers: Int,
    val activeMembers: Int,
    val upcomingRenewals: Int,
    val expiredMembers: Int,
)

object Dashboard {
    fun compute(
        members: List<MemberE>,
        memberships: List<MembershipE>,
        payments: List<PaymentE>,
        expenses: List<ExpenseE>,
        today: LocalDate,
        canSeeMoney: Boolean,
    ): DashboardStats {
        val todayIso = today.toString()
        val monthKey = todayIso.take(7)
        val liveMembers = members.filter { !it.archived }.associateBy { it.id }
        val payByMs = payments.filter { it.membershipId != null }.groupBy { it.membershipId!! }

        val todayPaise = payments.filter { it.paidOn == todayIso }.sumOf { Ledger.signed(it) }
        val monthPaise = payments.filter { it.paidOn.take(7) == monthKey }.sumOf { Ledger.signed(it) }
        val monthExp = expenses.filter { !it.deleted && it.spentOn.take(7) == monthKey }.sumOf { it.amountPaise }

        var pending = 0L
        val pendingMembers = HashSet<String>()
        for (ms in memberships) {
            if (!liveMembers.containsKey(ms.memberId)) continue
            val bal = Ledger.balance(ms, payByMs[ms.id].orEmpty())
            if (bal > 0) { pending += bal; pendingMembers.add(ms.memberId) }
        }

        val activeMs = memberships.filter { it.status == Ledger.ACTIVE && liveMembers.containsKey(it.memberId) }
        val active = activeMs.filter { it.startOn <= todayIso && todayIso <= it.endOn }.map { it.memberId }.toSet().size
        val limit = today.plusDays(7).toString()
        val latestEnd = activeMs.groupBy { it.memberId }.mapValues { e -> e.value.maxOf { it.endOn } }
        // Count members, not membership rows: duplicate/overlapping active rows must not
        // inflate the renewal number shown on the dashboard.
        val renewals = activeMs.asSequence()
            .filter { it.endOn >= todayIso && it.endOn <= limit && latestEnd[it.memberId] == it.endOn }
            .map { it.memberId }
            .toSet()
            .size
        // A member who has never purchased a plan is NO_PLAN, not an expired member.
        val expired = liveMembers.keys.count { id -> latestEnd[id]?.let { it < todayIso } == true }

        return DashboardStats(
            todayPaise = todayPaise,
            monthPaise = if (canSeeMoney) monthPaise else null,
            monthExpensesPaise = if (canSeeMoney) monthExp else null,
            pendingPaise = pending,
            pendingMembers = pendingMembers.size,
            activeMembers = active,
            upcomingRenewals = renewals,
            expiredMembers = expired,
        )
    }
}

enum class MemberState { ACTIVE, EXPIRING, EXPIRED, NO_PLAN }

data class MemberRow(
    val member: MemberE,
    val state: MemberState,
    val endOn: LocalDate?,
    val daysLeft: Int?,
    val balancePaise: Long,
    val planName: String?,
)

object MemberRows {
    fun build(
        members: List<MemberE>,
        memberships: List<MembershipE>,
        payments: List<PaymentE>,
        today: LocalDate,
    ): List<MemberRow> {
        val msByMember = memberships.groupBy { it.memberId }
        val payByMs = payments.filter { it.membershipId != null }.groupBy { it.membershipId!! }
        return members.map { m ->
            val all = msByMember[m.id].orEmpty()
            val latest = all.filter { it.status == Ledger.ACTIVE }.maxByOrNull { it.endOn }
            val balance = all.sumOf { maxOf(0L, Ledger.balance(it, payByMs[it.id].orEmpty())) }
            val end = latest?.let { Dates.parseOrNull(it.endOn) }
            val days = end?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() }
            val state = if (latest == null || end == null || days == null) MemberState.NO_PLAN
            else if (days < 0) MemberState.EXPIRED
            else if (days <= 7) MemberState.EXPIRING
            else MemberState.ACTIVE
            MemberRow(m, state, end, days, balance, latest?.planName)
        }
    }
}

data class MonthPoint(val month: YearMonth, val collected: Long, val refunded: Long, val expenses: Long) {
    val net: Long get() = collected - refunded - expenses
}

object Reports {
    /** [count] months ending at [last], oldest first. */
    fun monthly(payments: List<PaymentE>, expenses: List<ExpenseE>, last: YearMonth, count: Int): List<MonthPoint> =
        (count - 1 downTo 0).map { back ->
            val ym = last.minusMonths(back.toLong())
            val key = ym.toString() // yyyy-MM
            val pay = payments.filter { it.paidOn.take(7) == key }
            MonthPoint(
                month = ym,
                collected = pay.filter { it.kind == Ledger.PAYMENT }.sumOf { it.amountPaise },
                refunded = pay.filter { it.kind == Ledger.REFUND }.sumOf { it.amountPaise },
                expenses = expenses.filter { !it.deleted && it.spentOn.take(7) == key }.sumOf { it.amountPaise },
            )
        }

    fun expenseByCategory(expenses: List<ExpenseE>, month: YearMonth): List<Pair<String, Long>> =
        expenses.filter { !it.deleted && it.spentOn.take(7) == month.toString() }
            .groupBy { it.category }.map { it.key to it.value.sumOf { e -> e.amountPaise } }
            .sortedByDescending { it.second }
}

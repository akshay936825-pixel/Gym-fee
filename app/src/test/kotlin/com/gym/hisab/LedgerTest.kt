package com.gym.hisab

import com.gym.hisab.core.Dashboard
import com.gym.hisab.core.Ledger
import com.gym.hisab.core.MemberRows
import com.gym.hisab.core.MemberState
import com.gym.hisab.core.Reports
import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.data.local.MemberE
import com.gym.hisab.data.local.MembershipE
import com.gym.hisab.data.local.PaymentE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class LedgerTest {
    private val g = "gym"
    private val today = LocalDate.of(2026, 10, 26)

    private fun member(id: String, archived: Boolean = false) = MemberE(id, g, "M$id", null, "2026-01-01", archived = archived)
    private fun ms(id: String, mid: String, start: String, end: String, price: Long, disc: Long = 0, status: String = "active") =
        MembershipE(id, g, mid, null, "Plan", start, end, price, disc, status)
    private fun pay(id: String, mid: String, msId: String?, kind: String, amt: Long, on: String) =
        PaymentE(id, g, mid, msId, kind, amt, "cash", on, null, "R-$id")

    // Same scenario as supabase/tests/rls_and_ledger_test.sql: 1500 due, 1000 paid, 200 refunded.
    @Test fun matchesSqlScenario() {
        val members = listOf(member("a"))
        val memberships = listOf(ms("s1", "a", "2026-10-26", "2026-11-25", 150000))
        val payments = listOf(
            pay("p1", "a", "s1", Ledger.PAYMENT, 100000, "2026-10-26"),
            pay("p2", "a", "s1", Ledger.REFUND, 20000, "2026-10-26"),
        )
        val s = Dashboard.compute(members, memberships, payments, emptyList(), today, canSeeMoney = true)
        assertEquals(80000L, s.todayPaise)
        assertEquals(70000L, s.pendingPaise)
        assertEquals(1, s.pendingMembers)
        assertEquals(1, s.activeMembers)
    }

    @Test fun staffDoesNotGetMonthlyTotals() {
        val s = Dashboard.compute(emptyList(), emptyList(), emptyList(), emptyList(), today, canSeeMoney = false)
        assertNull(s.monthPaise); assertNull(s.monthExpensesPaise)
    }

    @Test fun partialPaymentsAndDiscount() {
        val m = ms("s", "a", "2026-10-01", "2026-10-31", 100000, disc = 10000)
        assertEquals(90000L, Ledger.due(m))
        assertEquals(90000L, Ledger.balance(m, emptyList()))
        assertEquals(40000L, Ledger.balance(m, listOf(pay("p", "a", "s", Ledger.PAYMENT, 50000, "2026-10-02"))))
        assertEquals(0L, Ledger.balance(m, listOf(pay("p", "a", "s", Ledger.PAYMENT, 90000, "2026-10-02"))))
    }

    @Test fun cancelledMembershipOwesNothing() {
        val m = ms("s", "a", "2026-10-01", "2026-10-10", 100000, status = Ledger.CANCELLED)
        assertEquals(0L, Ledger.balance(m, emptyList()))
    }

    @Test fun planEndDates() {
        assertEquals(LocalDate.of(2026, 2, 14), Ledger.endDate(LocalDate.of(2026, 1, 15), 1))
        assertEquals(LocalDate.of(2026, 2, 27), Ledger.endDate(LocalDate.of(2026, 1, 31), 1))
        assertEquals(LocalDate.of(2027, 1, 14), Ledger.endDate(LocalDate.of(2026, 1, 15), 12))
        assertEquals(LocalDate.of(2026, 7, 14), Ledger.endDate(LocalDate.of(2026, 1, 15), 6))
    }

    @Test fun renewalContinuesFromOldEndOnlyIfStillValid() {
        assertEquals(LocalDate.of(2026, 11, 1), Ledger.renewalStart(LocalDate.of(2026, 10, 31), today))
        assertEquals(today, Ledger.renewalStart(LocalDate.of(2026, 10, 1), today))
        assertEquals(today, Ledger.renewalStart(null, today))
    }

    @Test fun renewalsAndExpiredCounts() {
        val members = listOf(member("a"), member("b"), member("c"), member("arch", archived = true))
        val memberships = listOf(
            ms("s1", "a", "2026-10-01", "2026-10-30", 100000),             // ends in 4 days -> renewal
            ms("s2", "b", "2026-08-01", "2026-08-31", 100000),             // expired
            ms("s3", "c", "2026-09-01", "2026-10-29", 100000),             // ends in 3 days...
            ms("s4", "c", "2026-10-30", "2026-12-28", 100000),             // ...but already renewed -> not a renewal
            ms("s5", "arch", "2026-10-01", "2026-10-30", 100000),          // archived members are ignored
        )
        val s = Dashboard.compute(members, memberships, emptyList(), emptyList(), today, true)
        assertEquals(1, s.upcomingRenewals)
        assertEquals(1, s.expiredMembers)
        assertEquals(2, s.activeMembers) // a and c (c's first membership is current today)
    }

    @Test fun membersWithoutPlansAreNotCountedAsExpired() {
        val members = listOf(member("no-plan"), member("expired"))
        val memberships = listOf(ms("old", "expired", "2026-07-01", "2026-07-31", 100000))
        val s = Dashboard.compute(members, memberships, emptyList(), emptyList(), today, true)
        assertEquals(1, s.expiredMembers)
    }

    @Test fun duplicateMembershipRowsDoNotDoubleCountRenewals() {
        val members = listOf(member("a"))
        val memberships = listOf(
            ms("s1", "a", "2026-10-01", "2026-10-30", 100000),
            ms("s2", "a", "2026-09-01", "2026-10-30", 100000),
        )
        val s = Dashboard.compute(members, memberships, emptyList(), emptyList(), today, true)
        assertEquals(1, s.upcomingRenewals)
    }

    @Test fun memberRowStates() {
        val members = listOf(member("a"), member("b"), member("c"), member("d"))
        val memberships = listOf(
            ms("s1", "a", "2026-10-20", "2026-12-31", 100000),
            ms("s2", "b", "2026-10-20", "2026-10-30", 100000),
            ms("s3", "c", "2026-08-01", "2026-08-31", 100000),
        )
        val rows = MemberRows.build(members, memberships, emptyList(), today).associateBy { it.member.id }
        assertEquals(MemberState.ACTIVE, rows["a"]!!.state)
        assertEquals(MemberState.EXPIRING, rows["b"]!!.state)
        assertEquals(MemberState.EXPIRED, rows["c"]!!.state)
        assertEquals(MemberState.NO_PLAN, rows["d"]!!.state)
        assertEquals(100000L, rows["a"]!!.balancePaise)
    }

    @Test fun monthlyReportIncomeVersusExpense() {
        val payments = listOf(
            pay("1", "a", null, Ledger.PAYMENT, 500000, "2026-10-05"),
            pay("2", "a", null, Ledger.REFUND, 50000, "2026-10-06"),
            pay("3", "a", null, Ledger.PAYMENT, 200000, "2026-09-20"),
        )
        val expenses = listOf(
            ExpenseE("e1", g, "Rent", 100000, "2026-10-01"),
            ExpenseE("e2", g, "Salary", 80000, "2026-10-02", deleted = true), // deleted never counts
            ExpenseE("e3", g, "Rent", 100000, "2026-09-01"),
        )
        val pts = Reports.monthly(payments, expenses, YearMonth.of(2026, 10), 2)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10)), pts.map { it.month })
        assertEquals(100000L, pts[0].net)                 // 200000 - 100000
        assertEquals(500000L, pts[1].collected)
        assertEquals(50000L, pts[1].refunded)
        assertEquals(100000L, pts[1].expenses)
        assertEquals(350000L, pts[1].net)                 // 500000 - 50000 - 100000
    }
}

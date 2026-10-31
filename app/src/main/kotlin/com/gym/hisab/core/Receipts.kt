package com.gym.hisab.core

import com.gym.hisab.data.local.ExpenseE
import com.gym.hisab.data.local.PaymentE

object Receipts {
    /** Plain text so it works in WhatsApp, SMS, and any messaging app. */
    fun text(gymName: String, p: PaymentE, memberName: String, planName: String?, balancePaise: Long?): String {
        val sb = StringBuilder()
        sb.appendLine(gymName)
        sb.appendLine(if (p.kind == Ledger.REFUND) "REFUND RECEIPT" else "PAYMENT RECEIPT")
        sb.appendLine("Receipt no: ${p.receiptNo}")
        sb.appendLine("Date: ${Dates.pretty(p.paidOn)}")
        sb.appendLine("Member: $memberName")
        if (!planName.isNullOrBlank()) sb.appendLine("Plan: $planName")
        sb.appendLine((if (p.kind == Ledger.REFUND) "Refunded: " else "Paid: ") + Money.format(p.amountPaise) + " (" + p.mode.uppercase() + ")")
        if (balancePaise != null) {
            sb.appendLine(if (balancePaise > 0) "Balance due: " + Money.format(balancePaise) else "Balance: Nil")
        }
        sb.append("Thank you!")
        return sb.toString()
    }

    fun reminder(gymName: String, memberName: String, balancePaise: Long, endOn: String?): String {
        val tail = if (endOn != null) " Your membership is valid till ${Dates.pretty(endOn)}." else ""
        return "Hello $memberName, this is a friendly reminder from $gymName. " +
            "Your pending fee is ${Money.format(balancePaise)}.$tail Thank you!"
    }

    fun renewalReminder(gymName: String, memberName: String, endOn: String): String =
        "Hello $memberName, your membership at $gymName ends on ${Dates.pretty(endOn)}. " +
            "Renew soon to keep your streak going!"
}

object Exports {
    fun members(rows: List<MemberRow>): String {
        val sb = StringBuilder(Csv.row("Name", "Phone", "Joined", "Plan", "Valid until", "Status", "Balance due (INR)")).append('\n')
        for (r in rows) {
            sb.append(Csv.row(r.member.name, r.member.phone, r.member.joinedOn, r.planName, r.endOn?.toString(),
                r.state.name.lowercase(), Money.plain(r.balancePaise))).append('\n')
        }
        return sb.toString()
    }

    fun payments(list: List<PaymentE>, nameOf: (String) -> String): String {
        val sb = StringBuilder(Csv.row("Receipt", "Date", "Type", "Member", "Amount (INR)", "Mode", "Note")).append('\n')
        for (p in list) {
            sb.append(Csv.row(p.receiptNo, p.paidOn, p.kind, nameOf(p.memberId), Money.plain(p.amountPaise), p.mode, p.note)).append('\n')
        }
        return sb.toString()
    }

    fun expenses(list: List<ExpenseE>): String {
        val sb = StringBuilder(Csv.row("Date", "Category", "Amount (INR)", "Note")).append('\n')
        for (e in list) sb.append(Csv.row(e.spentOn, e.category, Money.plain(e.amountPaise), e.note)).append('\n')
        return sb.toString()
    }
}

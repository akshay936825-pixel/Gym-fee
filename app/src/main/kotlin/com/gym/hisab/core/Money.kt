package com.gym.hisab.core

import java.math.BigDecimal
import java.math.RoundingMode

/** All money is Long paise. Formatting uses Indian digit grouping: ₹1,23,456.50 */
object Money {
    fun format(paise: Long): String {
        val neg = paise < 0
        val abs = if (paise == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(paise)
        val rupees = abs / 100
        val rem = (abs % 100).toInt()
        val grouped = group(rupees.toString())
        val body = if (rem == 0) grouped else grouped + "." + rem.toString().padStart(2, '0')
        return (if (neg) "-" else "") + "₹" + body
    }

    private fun group(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        var rest = digits.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }

    /** "1500" or "1500.50" - for CSV files and edit fields (no symbol, no grouping). */
    fun plain(paise: Long): String {
        val abs = Math.abs(paise)
        val r = abs / 100
        val p = (abs % 100).toInt()
        val s = if (p == 0) r.toString() else r.toString() + "." + p.toString().padStart(2, '0')
        return if (paise < 0) "-$s" else s
    }

    /** Parses what a person types ("1,500", "₹ 1500.50"). Returns null if invalid, negative or > ₹10 crore. */
    fun parseToPaise(input: String): Long? {
        val t = input.trim().replace(",", "").replace("₹", "").trim()
        if (t.isEmpty()) return null
        val bd = try { BigDecimal(t) } catch (e: NumberFormatException) { return null }
        if (bd.signum() < 0) return null
        if (bd.compareTo(BigDecimal("100000000")) > 0) return null
        return try {
            bd.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).toLong()
        } catch (e: ArithmeticException) {
            null // more than 2 decimal places
        }
    }
}

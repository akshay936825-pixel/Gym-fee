package com.gym.hisab.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object Dates {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val pretty = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val monthFmt = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)

    fun today(): LocalDate = LocalDate.now(IST)
    fun parse(s: String): LocalDate = LocalDate.parse(s)
    fun parseOrNull(s: String?): LocalDate? = try { if (s.isNullOrBlank()) null else LocalDate.parse(s) } catch (e: Exception) { null }
    fun pretty(d: LocalDate): String = d.format(pretty)
    fun pretty(iso: String): String = parseOrNull(iso)?.let { pretty(it) } ?: iso
    fun monthLabel(ym: YearMonth): String = ym.format(monthFmt)
}

object Ids {
    fun newId(): String = UUID.randomUUID().toString()

    /** Same member + same day => same id on every phone, so a visit can never be counted twice. */
    fun attendanceId(gymId: String, memberId: String, day: String): String =
        UUID.nameUUIDFromBytes("$gymId|$memberId|$day".toByteArray(Charsets.UTF_8)).toString()

    /** Stable id derived from a request id, so one save action always produces the same row ids. */
    fun derived(seed: String, tag: String): String =
        UUID.nameUUIDFromBytes("$seed|$tag".toByteArray(Charsets.UTF_8)).toString()

    /** Example: HB-261026-4F2A. Unique per gym (database enforces it). */
    fun receiptNo(day: LocalDate): String {
        val d = day.format(DateTimeFormatter.ofPattern("yyMMdd"))
        val r = UUID.randomUUID().toString().replace("-", "").take(5).uppercase(Locale.ROOT)
        return "HB-$d-$r"
    }
}

object Csv {
    /** Escapes one row. Cells that start with = + - @ get a leading ' so Excel never runs them as formulas. */
    fun row(vararg cells: Any?): String = cells.joinToString(",") { cell(it) }

    private fun risky(s: String): Boolean {
        if (s.isEmpty()) return false
        val c = s[0]
        if (c == '=' || c == '@') return true
        if (c == '+' || c == '-') return s.drop(1).any { !it.isDigit() && it != '.' && it != ' ' }
        return false
    }

    private fun cell(v: Any?): String {
        var s = v?.toString() ?: ""
        if (risky(s)) s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

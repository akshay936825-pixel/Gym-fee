package com.gym.hisab

import com.gym.hisab.core.Csv
import com.gym.hisab.core.Ids
import com.gym.hisab.core.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoneyAndCsvTest {
    @Test fun formatsIndianGrouping() {
        assertEquals("₹0", Money.format(0))
        assertEquals("₹999", Money.format(99900))
        assertEquals("₹1,500", Money.format(150000))
        assertEquals("₹1,23,456.78", Money.format(12345678))
        assertEquals("₹1,00,00,000", Money.format(1_000_000_000))
        assertEquals("-₹250.50", Money.format(-25050))
    }

    @Test fun parsesWhatPeopleType() {
        assertEquals(150000L, Money.parseToPaise("1500"))
        assertEquals(150050L, Money.parseToPaise("1,500.50"))
        assertEquals(150000L, Money.parseToPaise("₹ 1500"))
        assertEquals(5L, Money.parseToPaise("0.05"))
    }

    @Test fun rejectsBadMoney() {
        assertNull(Money.parseToPaise(""))
        assertNull(Money.parseToPaise("abc"))
        assertNull(Money.parseToPaise("-5"))
        assertNull(Money.parseToPaise("12.345"))        // fractions of a paisa are never silently rounded
        assertNull(Money.parseToPaise("999999999999"))  // absurdly large
    }

    @Test fun plainRoundTrips() {
        for (p in listOf(0L, 5L, 100L, 150050L, 99999999L)) assertEquals(p, Money.parseToPaise(Money.plain(p)))
    }

    @Test fun csvEscapesAndBlocksFormulas() {
        assertEquals("a,\"b,c\",\"say \"\"hi\"\"\"", Csv.row("a", "b,c", "say \"hi\""))
        assertEquals("'=SUM(A1)", Csv.row("=SUM(A1)"))
        assertEquals("'@cmd", Csv.row("@cmd"))
        assertEquals("'-cmd", Csv.row("-cmd"))
        assertEquals("-500", Csv.row("-500"))          // real negative numbers stay numbers
        assertEquals("+919876543210", Csv.row("+919876543210")) // phone numbers stay intact
        assertEquals("", Csv.row(null))
    }

    @Test fun attendanceIdIsDeterministicPerMemberAndDay() {
        val a = Ids.attendanceId("g", "m", "2026-10-26")
        assertEquals(a, Ids.attendanceId("g", "m", "2026-10-26"))
        assertNotEquals(a, Ids.attendanceId("g", "m", "2026-10-27"))
        assertNotEquals(a, Ids.attendanceId("g", "other", "2026-10-26"))
    }

    @Test fun receiptNumbersLookRightAndDiffer() {
        val d = LocalDate.of(2026, 10, 26)
        val r1 = Ids.receiptNo(d); val r2 = Ids.receiptNo(d)
        assertTrue(r1.startsWith("HB-261026-"))
        assertNotEquals(r1, r2)
    }
}

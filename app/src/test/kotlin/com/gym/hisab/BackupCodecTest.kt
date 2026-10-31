package com.gym.hisab

import com.gym.hisab.data.backup.BackupCodec
import com.gym.hisab.data.backup.BackupException
import com.gym.hisab.data.backup.Snapshot
import com.gym.hisab.data.local.MemberE
import com.gym.hisab.data.local.MembershipE
import com.gym.hisab.data.local.PaymentE
import com.gym.hisab.data.local.PlanE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {
    private fun sample() = Snapshot(
        gymName = "Iron Temple",
        plans = listOf(PlanE("p1", "g", "Monthly", 1, 100000)),
        members = listOf(MemberE("m1", "g", "Rahul Sharma", "9876543210", "2026-10-01")),
        memberships = listOf(MembershipE("s1", "g", "m1", "p1", "Monthly", "2026-10-01", "2026-10-31", 100000, 0)),
        payments = listOf(
            PaymentE("pay1", "g", "m1", "s1", "payment", 60000, "upi", "2026-10-01", null, "HB-261001-AAAAA"),
            PaymentE("pay2", "g", "m1", "s1", "refund", 10000, "cash", "2026-10-02", "wrong entry", "HB-261002-BBBBB"),
        ),
    )

    @Test fun roundTripKeepsEveryRowAndPaise() {
        val text = BackupCodec.encode(sample(), "2026-10-31T10:00:00Z")
        val back = BackupCodec.decode(text)
        assertEquals(sample(), back.snapshot)
        assertEquals(60000L, back.snapshot.payments[0].amountPaise)
        assertEquals("refund", back.snapshot.payments[1].kind)
    }

    @Test fun editedAmountIsRefused() {
        val text = BackupCodec.encode(sample(), "2026-10-31T10:00:00Z").replace("60000", "90000")
        try { BackupCodec.decode(text); fail("tampered backup must be refused") }
        catch (e: BackupException) { assertTrue(e.message!!.contains("damaged")) }
    }

    @Test fun truncatedFileIsRefused() {
        val text = BackupCodec.encode(sample(), "2026-10-31T10:00:00Z")
        try { BackupCodec.decode(text.take(text.length / 2)); fail("truncated backup must be refused") }
        catch (e: BackupException) { /* expected */ }
    }

    @Test fun randomJsonIsNotABackup() {
        try { BackupCodec.decode("""{"hello":"world"}"""); fail() } catch (e: BackupException) { /* expected */ }
    }
}

package com.gym.hisab.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface HisabDao {
    // ---- live lists for the UI ----
    @Query("SELECT * FROM members WHERE gymId = :g ORDER BY name COLLATE NOCASE")
    fun members(g: String): Flow<List<MemberE>>

    @Query("SELECT * FROM memberships WHERE gymId = :g")
    fun memberships(g: String): Flow<List<MembershipE>>

    @Query("SELECT * FROM payments WHERE gymId = :g ORDER BY paidOn DESC, receiptNo DESC")
    fun payments(g: String): Flow<List<PaymentE>>

    @Query("SELECT * FROM expenses WHERE gymId = :g AND deleted = 0 ORDER BY spentOn DESC")
    fun expenses(g: String): Flow<List<ExpenseE>>

    @Query("SELECT * FROM plans WHERE gymId = :g AND active = 1 ORDER BY durationMonths, name")
    fun plans(g: String): Flow<List<PlanE>>

    @Query("SELECT * FROM attendance WHERE gymId = :g AND attendedOn = :day AND present = 1")
    fun attendanceOn(g: String, day: String): Flow<List<AttendanceE>>

    @Query("SELECT * FROM attendance WHERE gymId = :g AND memberId = :m AND present = 1 ORDER BY attendedOn DESC")
    fun attendanceOfMember(g: String, m: String): Flow<List<AttendanceE>>

    // ---- single rows (used when pushing) ----
    @Query("SELECT * FROM plans WHERE id = :id") suspend fun plan(id: String): PlanE?
    @Query("SELECT * FROM members WHERE id = :id") suspend fun member(id: String): MemberE?
    @Query("SELECT * FROM memberships WHERE id = :id") suspend fun membership(id: String): MembershipE?
    @Query("SELECT * FROM payments WHERE id = :id") suspend fun payment(id: String): PaymentE?
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun expense(id: String): ExpenseE?
    @Query("SELECT * FROM attendance WHERE id = :id") suspend fun attendance(id: String): AttendanceE?

    // ---- local-only helpers ----
    @Query("SELECT COUNT(*) FROM plans") suspend fun planCount(): Int
    @Query("SELECT * FROM payments WHERE membershipId = :ms") suspend fun paymentsOfMembership(ms: String): List<PaymentE>
    @Query("SELECT * FROM payments WHERE memberId = :m AND membershipId IS NULL") suspend fun looseMemberPayments(m: String): List<PaymentE>

    // Whole-table reads for backup/export (one consistent snapshot inside a transaction).
    @Query("SELECT * FROM plans") suspend fun allPlans(): List<PlanE>
    @Query("SELECT * FROM members") suspend fun allMembers(): List<MemberE>
    @Query("SELECT * FROM memberships") suspend fun allMemberships(): List<MembershipE>
    @Query("SELECT * FROM payments ORDER BY paidOn, receiptNo") suspend fun allPayments(): List<PaymentE>
    @Query("SELECT * FROM expenses") suspend fun allExpenses(): List<ExpenseE>
    @Query("SELECT * FROM attendance") suspend fun allAttendance(): List<AttendanceE>

    // IGNORE = never overwrite or destroy a row that already exists (payments are history).
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPaymentIfAbsent(p: PaymentE): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPlansIfAbsent(v: List<PlanE>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMembersIfAbsent(v: List<MemberE>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMembershipsIfAbsent(v: List<MembershipE>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPaymentsIfAbsent(v: List<PaymentE>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertExpensesIfAbsent(v: List<ExpenseE>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAttendanceIfAbsent(v: List<AttendanceE>): List<Long>

    // ---- writes ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlans(v: List<PlanE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMembers(v: List<MemberE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMemberships(v: List<MembershipE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPayments(v: List<PaymentE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertExpenses(v: List<ExpenseE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAttendance(v: List<AttendanceE>)

    // ---- outbox ----
    @Insert suspend fun enqueue(o: OutboxE): Long
    @Query("SELECT * FROM outbox WHERE failed = 0 ORDER BY seq") suspend fun pending(): List<OutboxE>
    @Query("SELECT COUNT(*) FROM outbox WHERE failed = 0") fun pendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM outbox WHERE failed = 1") fun failedCount(): Flow<Int>
    @Query("SELECT rowId FROM outbox WHERE tableName = :t") suspend fun outboxRowIds(t: String): List<String>
    @Query("DELETE FROM outbox WHERE seq = :seq") suspend fun dropOutbox(seq: Long)
    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :err, failed = :failed WHERE seq = :seq")
    suspend fun markOutbox(seq: Long, err: String?, failed: Boolean)
    @Query("UPDATE outbox SET failed = 0, attempts = 0, lastError = NULL WHERE failed = 1") suspend fun retryFailed()
    @Query("SELECT lastError FROM outbox WHERE failed = 1 ORDER BY seq LIMIT 1") suspend fun firstFailureMessage(): String?
}

@Database(
    entities = [PlanE::class, MemberE::class, MembershipE::class, PaymentE::class,
        ExpenseE::class, AttendanceE::class, OutboxE::class],
    version = 1,
    exportSchema = true, // schema JSON is now written to app/schemas so future migrations can be tested
)
abstract class HisabDb : RoomDatabase() {
    abstract fun dao(): HisabDao

    companion object {
        /**
         * Schema is still version 1: no migration is needed and existing rows are untouched.
         * There is deliberately NO fallbackToDestructiveMigration: a future schema change must
         * ship a real Migration, never wipe the owner's records.
         */
        fun create(ctx: Context): HisabDb =
            Room.databaseBuilder(ctx.applicationContext, HisabDb::class.java, "hisab.db")
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // WAL + synchronous=FULL: a committed payment is flushed to disk even if the
                        // phone loses power right after the "saved" confirmation.
                        db.query("PRAGMA synchronous=FULL").close()
                    }
                })
                .build()
    }
}

package com.gym.hisab.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// These classes are both Room rows AND the JSON sent to / received from Supabase (PostgREST).
// Money is always integer paise. Dates are ISO yyyy-MM-dd strings (sortable, same as Postgres).

@Serializable
@Entity(tableName = "plans", indices = [Index("gymId")])
data class PlanE(
    @PrimaryKey val id: String,
    @SerialName("gym_id") val gymId: String,
    val name: String,
    @SerialName("duration_months") val durationMonths: Int,
    @SerialName("price_paise") val pricePaise: Long,
    val active: Boolean = true,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(tableName = "members", indices = [Index("gymId")])
data class MemberE(
    @PrimaryKey val id: String,
    @SerialName("gym_id") val gymId: String,
    val name: String,
    val phone: String? = null,
    @SerialName("joined_on") val joinedOn: String,
    val notes: String? = null,
    val archived: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(tableName = "memberships", indices = [Index("gymId"), Index("memberId")])
data class MembershipE(
    @PrimaryKey val id: String,
    @SerialName("gym_id") val gymId: String,
    @SerialName("member_id") val memberId: String,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("plan_name") val planName: String,
    @SerialName("start_on") val startOn: String,
    @SerialName("end_on") val endOn: String,
    @SerialName("price_paise") val pricePaise: Long,
    @SerialName("discount_paise") val discountPaise: Long = 0,
    val status: String = "active", // active | cancelled
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(tableName = "payments", indices = [Index("gymId"), Index("memberId"), Index("membershipId")])
data class PaymentE(
    @PrimaryKey val id: String,
    @SerialName("gym_id") val gymId: String,
    @SerialName("member_id") val memberId: String,
    @SerialName("membership_id") val membershipId: String? = null,
    val kind: String = "payment", // payment | refund
    @SerialName("amount_paise") val amountPaise: Long,
    val mode: String = "cash", // cash | upi | card | other
    @SerialName("paid_on") val paidOn: String,
    val note: String? = null,
    @SerialName("receipt_no") val receiptNo: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(tableName = "expenses", indices = [Index("gymId")])
data class ExpenseE(
    @PrimaryKey val id: String,
    @SerialName("gym_id") val gymId: String,
    val category: String,
    @SerialName("amount_paise") val amountPaise: Long,
    @SerialName("spent_on") val spentOn: String,
    val note: String? = null,
    val deleted: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(tableName = "attendance", indices = [Index("gymId"), Index("attendedOn")])
data class AttendanceE(
    @PrimaryKey val id: String,
    @SerialName("gym_id") val gymId: String,
    @SerialName("member_id") val memberId: String,
    @SerialName("attended_on") val attendedOn: String,
    val present: Boolean = true,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** One pending change waiting to reach Supabase. The newest row state is read at push time. */
@Entity(tableName = "outbox")
data class OutboxE(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val tableName: String,
    val rowId: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val failed: Boolean = false,
)

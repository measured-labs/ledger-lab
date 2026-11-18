package com.jun.labs.ledger.service

import com.jun.labs.ledger.domain.EarnSource
import com.jun.labs.ledger.domain.EarnStatus
import com.jun.labs.ledger.domain.HistoryType
import com.jun.labs.ledger.domain.PointEarn
import com.jun.labs.ledger.domain.PointHistory
import com.jun.labs.ledger.domain.PointUse
import com.jun.labs.ledger.domain.UseStatus
import java.time.Instant

data class EarnResult(
    val earnId: Long,
    val userId: Long,
    val amount: Long,
    val remaining: Long,
    val source: EarnSource,
    val sourceRef: String,
    val status: EarnStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
) {
    companion object {
        fun from(e: PointEarn) = EarnResult(
            requireNotNull(e.id), e.userId, e.amount, e.remaining, e.source, e.sourceRef, e.status, e.expiresAt, e.createdAt,
        )
    }
}

data class UseResult(
    val useId: Long,
    val userId: Long,
    val amount: Long,
    val canceledAmount: Long,
    val orderRef: String,
    val status: UseStatus,
    val usedAt: Instant,
) {
    companion object {
        fun from(u: PointUse) = UseResult(requireNotNull(u.id), u.userId, u.amount, u.canceledAmount, u.orderRef, u.status, u.usedAt)
    }
}

data class BalanceResult(val userId: Long, val balance: Long)

data class HistoryResult(
    val historyId: Long,
    val type: HistoryType,
    val amount: Long,
    val refId: Long,
    val balanceAfter: Long,
    val createdAt: Instant,
) {
    companion object {
        fun from(h: PointHistory) = HistoryResult(requireNotNull(h.id), h.type, h.amount, h.refId, h.balanceAfter, h.createdAt)
    }
}

package com.jun.labs.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 이력 로그 1행 = 상태 변화 1건(ADR-000 §2). 쓰기만 하고 아무도 읽어 계산하지 않는다 — 진실의 원천은 잔액 컬럼과 remaining.
 * amount는 변화량의 크기(0 이상 — 0 = 다 쓴 적립 건의 취소 · 만료), balanceAfter는 변화 뒤 지갑 잔액.
 */
@Entity
@Table(name = "point_history")
class PointHistory protected constructor(
    userId: Long,
    type: HistoryType,
    amount: Long,
    refId: Long,
    balanceAfter: Long,
    createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "user_id", nullable = false)
    var userId: Long = userId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: HistoryType = type
        protected set

    @Column(nullable = false)
    var amount: Long = amount
        protected set

    @Column(name = "ref_id", nullable = false)
    var refId: Long = refId
        protected set

    @Column(name = "balance_after", nullable = false)
    var balanceAfter: Long = balanceAfter
        protected set

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = createdAt
        protected set

    companion object {
        fun record(wallet: PointWallet, type: HistoryType, amount: Long, refId: Long, now: Instant): PointHistory {
            require(amount >= 0) { "이력 amount는 0 이상: $amount" }
            require(refId > 0) { "refId: $refId" }
            return PointHistory(wallet.userId, type, amount, refId, wallet.balance, PointRules.normalize(now))
        }
    }
}

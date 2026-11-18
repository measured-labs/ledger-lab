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
 * PointUse 애그리거트 루트 — 사용 1건과 취소 누적액(ADR-000 §2.1).
 * 불변식: ④ 0 ≤ canceledAmount ≤ amount
 * ⑤ CANCELED ⇔ canceledAmount = amount · PARTIALLY_CANCELED ⇔ 0 < canceledAmount < amount.
 * 어느 적립 건에서 깎았는지는 모른다 — 취소는 새 적립으로 되돌린다.
 */
@Entity
@Table(name = "point_use")
class PointUse protected constructor(
    userId: Long,
    amount: Long,
    orderRef: String,
    now: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "user_id", nullable = false)
    var userId: Long = userId
        protected set

    @Column(nullable = false)
    var amount: Long = amount
        protected set

    @Column(name = "canceled_amount", nullable = false)
    var canceledAmount: Long = 0
        protected set

    @Column(name = "order_ref", nullable = false, length = PointRules.REF_MAX)
    var orderRef: String = orderRef
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: UseStatus = UseStatus.USED
        protected set

    @Column(name = "used_at", nullable = false)
    var usedAt: Instant = now
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = now
        protected set

    fun cancelableAmount(): Long = amount - canceledAmount

    /**
     * 판정 순서: 금액 형식(400) → 상태(409 USE_NOT_CANCELABLE) → 남은 사용액(409 CANCEL_AMOUNT_EXCEEDED).
     * cancelAmount null = 남은 사용액 전부. 실제 취소액을 돌려준다.
     */
    fun cancel(cancelAmount: Long?, now: Instant): Long {
        if (cancelAmount != null) PointRules.checkAmount(cancelAmount, "cancelAmount")
        if (!status.cancelable) throw PointException(ErrorCode.USE_NOT_CANCELABLE)
        val amountToCancel = cancelAmount ?: cancelableAmount()
        if (amountToCancel > cancelableAmount()) throw PointException(ErrorCode.CANCEL_AMOUNT_EXCEEDED)
        canceledAmount += amountToCancel
        status = if (canceledAmount == amount) UseStatus.CANCELED else UseStatus.PARTIALLY_CANCELED
        updatedAt = PointRules.normalize(now)
        return amountToCancel
    }

    companion object {
        fun use(userId: Long, amount: Long, orderRef: String, now: Instant): PointUse {
            PointRules.checkUserId(userId)
            PointRules.checkAmount(amount)
            PointRules.checkRef(orderRef, "orderRef")
            return PointUse(userId, amount, orderRef, PointRules.normalize(now))
        }
    }
}

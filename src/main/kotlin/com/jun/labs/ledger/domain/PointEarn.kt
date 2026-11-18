package com.jun.labs.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

/**
 * PointEarn 애그리거트 루트 — 적립 1건과 그 남은 금액(ADR-000 §2.1).
 * 불변식: ② 0 ≤ remaining ≤ amount ③ ACTIVE → CANCELED · ACTIVE → EXPIRED만 · CANCELED · EXPIRED면 remaining = 0.
 * 무엇을 얼마나 깎았는지는 남기지 않는다(사용-적립 연결 없음 — 베이스라인).
 */
@Entity
@Table(name = "point_earn")
class PointEarn protected constructor(
    userId: Long,
    amount: Long,
    source: EarnSource,
    sourceRef: String,
    expiresAt: Instant,
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

    @Column(nullable = false)
    var remaining: Long = amount
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var source: EarnSource = source
        protected set

    @Column(name = "source_ref", nullable = false, length = PointRules.REF_MAX)
    var sourceRef: String = sourceRef
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EarnStatus = EarnStatus.ACTIVE
        protected set

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = expiresAt
        protected set

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = now
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = now
        protected set

    /** 사용 대상: ACTIVE · remaining > 0 · 만료 전(expiresAt > now) */
    fun usableAt(now: Instant): Boolean = status == EarnStatus.ACTIVE && remaining > 0 && expiresAt.isAfter(now)

    /** 사용에서 이 건의 remaining을 깎는다 — 판정 순서: 금액 형식 → 상태 → 남은 금액(1 ≤ amount ≤ remaining) */
    fun consume(amount: Long, now: Instant) {
        require(amount in PointRules.AMOUNT_MIN..PointRules.AMOUNT_MAX) { "차감액 형식: $amount" }
        check(status == EarnStatus.ACTIVE && expiresAt.isAfter(now)) { "사용할 수 없는 적립 건: $id($status)" }
        require(amount <= remaining) { "차감액 $amount — 남은 금액 $remaining" }
        remaining -= amount
        updatedAt = PointRules.normalize(now)
    }

    /** 적립 취소 — 남은 만큼만 소멸(이미 쓴 몫은 회수하지 않는다). 소멸액(0 이상)을 돌려준다 */
    fun cancel(now: Instant): Long {
        if (status != EarnStatus.ACTIVE) throw PointException(ErrorCode.EARN_NOT_CANCELABLE)
        return close(EarnStatus.CANCELED, now)
    }

    /** 만료 배치 — expiresAt ≤ now인 ACTIVE 건만. 소멸액(0 이상)을 돌려준다 */
    fun expire(now: Instant): Long {
        check(status == EarnStatus.ACTIVE) { "만료는 ACTIVE에서만: $id($status)" }
        check(!expiresAt.isAfter(now)) { "아직 만료 전: $id(expiresAt=$expiresAt, now=$now)" }
        return close(EarnStatus.EXPIRED, now)
    }

    private fun close(to: EarnStatus, now: Instant): Long {
        val extinguished = remaining
        remaining = 0
        status = to
        updatedAt = PointRules.normalize(now)
        return extinguished
    }

    companion object {
        /** 적립 요청 — 유효기간은 요청이 정한다 */
        fun earn(userId: Long, amount: Long, sourceRef: String, expiresAt: Instant, now: Instant): PointEarn {
            PointRules.checkUserId(userId)
            PointRules.checkAmount(amount)
            PointRules.checkRef(sourceRef, "sourceRef")
            val at = PointRules.normalize(now)
            val expires = PointRules.normalize(expiresAt)
            PointRules.checkExpiresAt(expires, at)
            return PointEarn(userId, amount, EarnSource.EARN, sourceRef, expires, at)
        }

        /** 사용 취소 환원 — 원래 적립 건과 무관한 새 적립(source_ref = 사용 건 id · 유효기간 now + validFor, 상한을 넘으면 상한) */
        fun restore(userId: Long, amount: Long, useId: Long, validFor: Duration, now: Instant): PointEarn {
            PointRules.checkUserId(userId)
            PointRules.checkAmount(amount)
            require(useId > 0) { "useId: $useId" }
            require(!validFor.isNegative && !validFor.isZero) { "유효기간: $validFor" }
            val at = PointRules.normalize(now)
            val expires = minOf(at.plus(validFor), PointRules.EXPIRES_AT_MAX)
            return PointEarn(userId, amount, EarnSource.USE_CANCEL, useId.toString(), expires, at)
        }
    }
}

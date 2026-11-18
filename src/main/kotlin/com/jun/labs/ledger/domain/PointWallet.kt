package com.jun.labs.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * PointWallet 애그리거트 루트 — 사용자의 잔액 컬럼(ADR-000 §2.1).
 * 불변식: ① balance ≥ 0 — 사용([withdraw])만 잔액으로 거른다. 적립 취소 · 만료([deduct])는 거르지 않아 잔액이 이미 틀어졌으면 음수로 남는다.
 * 락 · 버전이 없어 동시 요청은 같은 잔액을 읽고 덮어쓸 수 있다(베이스라인).
 * user_id 유니크가 없어 지갑이 둘 이상일 수 있다 — 조회는 가장 작은 id를 쓴다.
 */
@Entity
@Table(name = "point_wallet")
class PointWallet protected constructor(
    userId: Long,
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
    var balance: Long = 0
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = now
        protected set

    /** 적립 · 사용 취소 환원 */
    fun deposit(amount: Long, now: Instant) {
        PointRules.checkAmount(amount)
        balance = Math.addExact(balance, amount)
        updatedAt = PointRules.normalize(now)
    }

    /** 사용 — 잔액 컬럼으로 먼저 거른다(모자라면 409 INSUFFICIENT_BALANCE) */
    fun withdraw(amount: Long, now: Instant) {
        PointRules.checkAmount(amount)
        if (balance < amount) throw PointException(ErrorCode.INSUFFICIENT_BALANCE)
        balance -= amount
        updatedAt = PointRules.normalize(now)
    }

    /**
     * 적립 취소 · 만료로 사라지는 적립 잔여를 뺀다(0 허용 — 다 쓴 적립 건).
     * 잔액으로 거르지 않는다: 정상 경로에서는 잔액 = Σ remaining이라 넘지 않지만, 동시 요청의 잃어버린 갱신으로
     * 잔액이 Σ remaining보다 작아졌으면 음수가 된다(①이 깨진 모습 그대로 남긴다 — 정합성 검증기가 센다, ADR-000 §8).
     * 여기서 예외를 던지면 그 사용자 하나 때문에 만료 배치 실행 전체가 롤백되고 다음 실행도 같은 건에서 막힌다.
     */
    fun deduct(amount: Long, now: Instant) {
        require(amount >= 0) { "음수 차감: $amount" }
        balance -= amount
        updatedAt = PointRules.normalize(now)
    }

    companion object {
        fun open(userId: Long, now: Instant): PointWallet {
            PointRules.checkUserId(userId)
            return PointWallet(userId, PointRules.normalize(now))
        }
    }
}

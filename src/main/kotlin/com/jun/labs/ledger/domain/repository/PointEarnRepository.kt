package com.jun.labs.ledger.domain.repository

import com.jun.labs.ledger.domain.EarnStatus
import com.jun.labs.ledger.domain.PointEarn
import org.springframework.data.domain.Limit
import org.springframework.data.repository.Repository
import java.time.Instant

/** save · find만 — 삭제는 노출하지 않는다. 쿼리는 파생 메서드만(ADR-000 §7) */
interface PointEarnRepository : Repository<PointEarn, Long> {
    fun save(earn: PointEarn): PointEarn

    fun findById(id: Long): PointEarn?

    /** 사용 대상: status · remaining > 0 · expiresAt > now, 만료 임박 순(같으면 id 순) */
    fun findByUserIdAndStatusAndRemainingGreaterThanAndExpiresAtAfterOrderByExpiresAtAscIdAsc(
        userId: Long,
        status: EarnStatus,
        remaining: Long,
        now: Instant,
    ): List<PointEarn>

    /** 만료 대상: status · expiresAt ≤ now, expiresAt 순(같으면 id 순) 최대 limit건 */
    fun findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(
        status: EarnStatus,
        now: Instant,
        limit: Limit,
    ): List<PointEarn>
}

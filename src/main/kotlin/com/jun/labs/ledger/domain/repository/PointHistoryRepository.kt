package com.jun.labs.ledger.domain.repository

import com.jun.labs.ledger.domain.PointHistory
import org.springframework.data.domain.Limit
import org.springframework.data.repository.Repository

/** save · find만 — 이력은 쓰기만 하는 로그다(수정 · 삭제 없음) */
interface PointHistoryRepository : Repository<PointHistory, Long> {
    fun save(history: PointHistory): PointHistory

    /** 최신순(id 내림차순) 최대 limit건 */
    fun findByUserIdOrderByIdDesc(userId: Long, limit: Limit): List<PointHistory>
}

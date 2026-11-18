package com.jun.labs.ledger.domain.repository

import com.jun.labs.ledger.domain.PointUse
import org.springframework.data.repository.Repository

/** save · find만 — 삭제는 노출하지 않는다 */
interface PointUseRepository : Repository<PointUse, Long> {
    fun save(use: PointUse): PointUse

    fun findById(id: Long): PointUse?
}

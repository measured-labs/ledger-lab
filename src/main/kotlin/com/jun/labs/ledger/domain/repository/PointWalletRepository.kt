package com.jun.labs.ledger.domain.repository

import com.jun.labs.ledger.domain.PointWallet
import org.springframework.data.repository.Repository

/** save · find만 — 삭제는 노출하지 않는다 */
interface PointWalletRepository : Repository<PointWallet, Long> {
    fun save(wallet: PointWallet): PointWallet

    /** 같은 user_id 지갑이 여럿이면 가장 작은 id(ADR-000 §5 — 막지 않고 조회 규칙만 정한다) */
    fun findFirstByUserIdOrderByIdAsc(userId: Long): PointWallet?
}

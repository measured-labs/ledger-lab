package com.jun.labs.ledger.service.impl

import com.jun.labs.ledger.domain.EarnStatus
import com.jun.labs.ledger.domain.HistoryType
import com.jun.labs.ledger.domain.PointHistory
import com.jun.labs.ledger.domain.PointRules
import com.jun.labs.ledger.domain.PointWallet
import com.jun.labs.ledger.domain.repository.PointEarnRepository
import com.jun.labs.ledger.domain.repository.PointHistoryRepository
import com.jun.labs.ledger.domain.repository.PointWalletRepository
import com.jun.labs.ledger.service.ExpirePointsUseCase
import com.jun.labs.ledger.service.LedgerProperties
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 만료 배치(ADR-000 §3): ACTIVE · expiresAt ≤ now 적립 건을 expiresAt 순(같으면 id 순)으로 최대 batch-size건 읽어
 * 각각 balance -= remaining · remaining = 0 · EXPIRED → 이력(소멸액, 0 허용). 실행 한 번 = 트랜잭션 하나.
 */
@Service
class ExpirePointsService(
    private val walletRepository: PointWalletRepository,
    private val earnRepository: PointEarnRepository,
    private val historyRepository: PointHistoryRepository,
    private val properties: LedgerProperties,
    private val clock: Clock,
) : ExpirePointsUseCase {

    @Transactional
    override fun expire(): Int {
        val now = PointRules.normalize(clock.instant())
        val targets = earnRepository.findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(
            EarnStatus.ACTIVE, now, Limit.of(properties.expiry.batchSize),
        )
        for (earn in targets) {
            val earnId = requireNotNull(earn.id)
            val extinguished = earn.expire(now)
            // 지갑 없는 적립 건(DB 조작으로만 생긴다) 하나 때문에 실행 전체가 롤백되지 않게 — 지갑을 만들어 빼고 음수 잔액으로 남긴다
            val wallet = walletRepository.findFirstByUserIdOrderByIdAsc(earn.userId)
                ?: walletRepository.save(PointWallet.open(earn.userId, now))
            wallet.deduct(extinguished, now)
            historyRepository.save(PointHistory.record(wallet, HistoryType.EXPIRE, extinguished, earnId, now))
        }
        return targets.size
    }
}

package com.jun.labs.ledger.service.impl

import com.jun.labs.ledger.domain.HistoryType
import com.jun.labs.ledger.domain.PointEarn
import com.jun.labs.ledger.domain.PointHistory
import com.jun.labs.ledger.domain.PointRules
import com.jun.labs.ledger.domain.PointWallet
import com.jun.labs.ledger.domain.repository.PointEarnRepository
import com.jun.labs.ledger.domain.repository.PointHistoryRepository
import com.jun.labs.ledger.domain.repository.PointWalletRepository
import com.jun.labs.ledger.service.EarnPointsCommand
import com.jun.labs.ledger.service.EarnPointsUseCase
import com.jun.labs.ledger.service.EarnResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 적립(ADR-000 §3): 지갑 조회(없으면 생성) → 적립 건 INSERT → balance += amount → 이력. 한 트랜잭션 · 락 없음.
 * 지갑은 조회 → 없으면 INSERT라 동시 첫 적립에서 둘 생길 수 있다(베이스라인).
 */
@Service
class EarnPointsService(
    private val walletRepository: PointWalletRepository,
    private val earnRepository: PointEarnRepository,
    private val historyRepository: PointHistoryRepository,
    private val clock: Clock,
) : EarnPointsUseCase {

    @Transactional
    override fun earn(command: EarnPointsCommand): EarnResult {
        val now = PointRules.normalize(clock.instant())
        val earn = PointEarn.earn(command.userId, command.amount, command.sourceRef, command.expiresAt, now)
        val wallet = walletRepository.findFirstByUserIdOrderByIdAsc(command.userId)
            ?: walletRepository.save(PointWallet.open(command.userId, now))
        val saved = earnRepository.save(earn)
        wallet.deposit(saved.amount, now)
        historyRepository.save(PointHistory.record(wallet, HistoryType.EARN, saved.amount, requireNotNull(saved.id), now))
        return EarnResult.from(saved)
    }
}

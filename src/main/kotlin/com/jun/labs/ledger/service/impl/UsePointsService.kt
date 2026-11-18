package com.jun.labs.ledger.service.impl

import com.jun.labs.ledger.domain.EarnStatus
import com.jun.labs.ledger.domain.ErrorCode
import com.jun.labs.ledger.domain.HistoryType
import com.jun.labs.ledger.domain.PointException
import com.jun.labs.ledger.domain.PointHistory
import com.jun.labs.ledger.domain.PointRules
import com.jun.labs.ledger.domain.PointUse
import com.jun.labs.ledger.domain.repository.PointEarnRepository
import com.jun.labs.ledger.domain.repository.PointHistoryRepository
import com.jun.labs.ledger.domain.repository.PointUseRepository
import com.jun.labs.ledger.domain.repository.PointWalletRepository
import com.jun.labs.ledger.service.UsePointsCommand
import com.jun.labs.ledger.service.UsePointsUseCase
import com.jun.labs.ledger.service.UseResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 사용(ADR-000 §3): 지갑 잔액으로 먼저 거른다 → 사용 가능한 적립 건을 만료 임박 순(같으면 id 순)으로 깎는다
 * → 깎은 합이 모자라면 409(롤백) → 사용 건 INSERT → 이력. 한 트랜잭션 · 락 없음.
 * 만료됐으나 배치 전인 적립 건은 잔액에는 남고 사용 대상에서는 빠진다(Q6 창 — 베이스라인).
 */
@Service
class UsePointsService(
    private val walletRepository: PointWalletRepository,
    private val earnRepository: PointEarnRepository,
    private val useRepository: PointUseRepository,
    private val historyRepository: PointHistoryRepository,
    private val clock: Clock,
) : UsePointsUseCase {

    @Transactional
    override fun use(command: UsePointsCommand): UseResult {
        val now = PointRules.normalize(clock.instant())
        val use = PointUse.use(command.userId, command.amount, command.orderRef, now)
        val wallet = walletRepository.findFirstByUserIdOrderByIdAsc(command.userId)
            ?: throw PointException(ErrorCode.INSUFFICIENT_BALANCE)
        wallet.withdraw(use.amount, now)

        var left = use.amount
        val usable = earnRepository.findByUserIdAndStatusAndRemainingGreaterThanAndExpiresAtAfterOrderByExpiresAtAscIdAsc(
            command.userId, EarnStatus.ACTIVE, 0, now,
        )
        for (earn in usable) {
            if (left == 0L) break
            val take = minOf(earn.remaining, left)
            earn.consume(take, now)
            left -= take
        }
        if (left > 0) throw PointException(ErrorCode.INSUFFICIENT_BALANCE, "사용 가능한 적립 건이 ${left}P 모자랍니다.")

        val saved = useRepository.save(use)
        historyRepository.save(PointHistory.record(wallet, HistoryType.USE, saved.amount, requireNotNull(saved.id), now))
        return UseResult.from(saved)
    }
}

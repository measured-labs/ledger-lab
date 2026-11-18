package com.jun.labs.ledger.service.impl

import com.jun.labs.ledger.domain.ErrorCode
import com.jun.labs.ledger.domain.HistoryType
import com.jun.labs.ledger.domain.PointException
import com.jun.labs.ledger.domain.PointHistory
import com.jun.labs.ledger.domain.PointRules
import com.jun.labs.ledger.domain.PointWallet
import com.jun.labs.ledger.domain.repository.PointEarnRepository
import com.jun.labs.ledger.domain.repository.PointHistoryRepository
import com.jun.labs.ledger.domain.repository.PointWalletRepository
import com.jun.labs.ledger.service.CancelEarnUseCase
import com.jun.labs.ledger.service.EarnResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 적립 취소(ADR-000 §3): 적립 건 조회(404) → ACTIVE 아니면 409 → 남은 만큼만 소멸 → balance -= 소멸액 → 이력(소멸액, 0 허용).
 * 이미 쓴 몫은 회수하지 않는다(베이스라인).
 */
@Service
class CancelEarnService(
    private val walletRepository: PointWalletRepository,
    private val earnRepository: PointEarnRepository,
    private val historyRepository: PointHistoryRepository,
    private val clock: Clock,
) : CancelEarnUseCase {

    @Transactional
    override fun cancel(earnId: Long): EarnResult {
        val earn = earnRepository.findById(earnId) ?: throw PointException(ErrorCode.EARN_NOT_FOUND)
        val now = PointRules.normalize(clock.instant())
        val extinguished = earn.cancel(now)
        // 지갑 없는 적립 건(DB 조작으로만 생긴다)도 막지 않는다 — 지갑을 만들어 빼고 음수 잔액으로 남긴다
        val wallet = walletRepository.findFirstByUserIdOrderByIdAsc(earn.userId)
            ?: walletRepository.save(PointWallet.open(earn.userId, now))
        wallet.deduct(extinguished, now)
        historyRepository.save(PointHistory.record(wallet, HistoryType.EARN_CANCEL, extinguished, earnId, now))
        return EarnResult.from(earn)
    }
}

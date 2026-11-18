package com.jun.labs.ledger.service.impl

import com.jun.labs.ledger.domain.ErrorCode
import com.jun.labs.ledger.domain.HistoryType
import com.jun.labs.ledger.domain.PointEarn
import com.jun.labs.ledger.domain.PointException
import com.jun.labs.ledger.domain.PointHistory
import com.jun.labs.ledger.domain.PointRules
import com.jun.labs.ledger.domain.PointWallet
import com.jun.labs.ledger.domain.repository.PointEarnRepository
import com.jun.labs.ledger.domain.repository.PointHistoryRepository
import com.jun.labs.ledger.domain.repository.PointUseRepository
import com.jun.labs.ledger.domain.repository.PointWalletRepository
import com.jun.labs.ledger.service.CancelUseCommand
import com.jun.labs.ledger.service.CancelUseUseCase
import com.jun.labs.ledger.service.LedgerProperties
import com.jun.labs.ledger.service.UseResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * 사용 취소(ADR-000 §3): 사용 건 조회 → 상태 · 금액 검사 → 새 적립 건(USE_CANCEL · now + valid-days) → balance += 취소액 → 이력.
 * 판정 순서: 입력(400) → 존재(404) → 상태(409) → 금액(409).
 * 원래 적립 건 · 유효기간은 되살리지 않는다 — 이미 만료된 적립에서 쓴 몫도 새 유효기간을 받는다(베이스라인).
 */
@Service
class CancelUseService(
    private val walletRepository: PointWalletRepository,
    private val earnRepository: PointEarnRepository,
    private val useRepository: PointUseRepository,
    private val historyRepository: PointHistoryRepository,
    private val properties: LedgerProperties,
    private val clock: Clock,
) : CancelUseUseCase {

    @Transactional
    override fun cancel(command: CancelUseCommand): UseResult {
        command.cancelAmount?.let { PointRules.checkAmount(it, "cancelAmount") }
        val use = useRepository.findById(command.useId) ?: throw PointException(ErrorCode.USE_NOT_FOUND)
        val now = PointRules.normalize(clock.instant())
        val useId = requireNotNull(use.id)
        val canceled = use.cancel(command.cancelAmount, now)

        earnRepository.save(PointEarn.restore(use.userId, canceled, useId, Duration.ofDays(properties.restore.validDays), now))
        val wallet = walletRepository.findFirstByUserIdOrderByIdAsc(use.userId)
            ?: walletRepository.save(PointWallet.open(use.userId, now))
        wallet.deposit(canceled, now)
        historyRepository.save(PointHistory.record(wallet, HistoryType.USE_CANCEL, canceled, useId, now))
        return UseResult.from(use)
    }
}

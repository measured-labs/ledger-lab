package com.jun.labs.ledger.service.impl

import com.jun.labs.ledger.domain.ErrorCode
import com.jun.labs.ledger.domain.PointException
import com.jun.labs.ledger.domain.PointRules
import com.jun.labs.ledger.domain.repository.PointEarnRepository
import com.jun.labs.ledger.domain.repository.PointHistoryRepository
import com.jun.labs.ledger.domain.repository.PointUseRepository
import com.jun.labs.ledger.domain.repository.PointWalletRepository
import com.jun.labs.ledger.service.BalanceResult
import com.jun.labs.ledger.service.EarnResult
import com.jun.labs.ledger.service.GetPointsUseCase
import com.jun.labs.ledger.service.GetPointsUseCase.Companion.HISTORY_LIMIT_MAX
import com.jun.labs.ledger.service.GetPointsUseCase.Companion.HISTORY_LIMIT_MIN
import com.jun.labs.ledger.service.HistoryResult
import com.jun.labs.ledger.service.UseResult
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetPointsService(
    private val walletRepository: PointWalletRepository,
    private val earnRepository: PointEarnRepository,
    private val useRepository: PointUseRepository,
    private val historyRepository: PointHistoryRepository,
) : GetPointsUseCase {

    override fun balance(userId: Long): BalanceResult {
        PointRules.checkUserId(userId)
        return BalanceResult(userId, walletRepository.findFirstByUserIdOrderByIdAsc(userId)?.balance ?: 0)
    }

    override fun history(userId: Long, limit: Int): List<HistoryResult> {
        PointRules.checkUserId(userId)
        if (limit !in HISTORY_LIMIT_MIN..HISTORY_LIMIT_MAX) throw PointException(ErrorCode.INVALID_REQUEST, "limit은 $HISTORY_LIMIT_MIN~$HISTORY_LIMIT_MAX")
        return historyRepository.findByUserIdOrderByIdDesc(userId, Limit.of(limit)).map(HistoryResult::from)
    }

    override fun earn(earnId: Long): EarnResult =
        EarnResult.from(earnRepository.findById(earnId) ?: throw PointException(ErrorCode.EARN_NOT_FOUND))

    override fun use(useId: Long): UseResult =
        UseResult.from(useRepository.findById(useId) ?: throw PointException(ErrorCode.USE_NOT_FOUND))
}

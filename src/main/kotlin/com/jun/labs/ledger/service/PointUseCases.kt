package com.jun.labs.ledger.service

interface EarnPointsUseCase {
    fun earn(command: EarnPointsCommand): EarnResult
}

interface UsePointsUseCase {
    fun use(command: UsePointsCommand): UseResult
}

interface CancelUseUseCase {
    fun cancel(command: CancelUseCommand): UseResult
}

interface CancelEarnUseCase {
    fun cancel(earnId: Long): EarnResult
}

interface ExpirePointsUseCase {
    /** 이번 실행에서 EXPIRED로 바꾼 적립 건 수. 실행 한 번 = 트랜잭션 하나 · 최대 batch-size건 · expiresAt 순(같으면 id 순) */
    fun expire(): Int
}

interface GetPointsUseCase {
    /** 지갑이 없으면 0 */
    fun balance(userId: Long): BalanceResult

    /** 최신순(id 내림차순) 최대 limit건 */
    fun history(userId: Long, limit: Int): List<HistoryResult>

    fun earn(earnId: Long): EarnResult

    fun use(useId: Long): UseResult

    companion object {
        const val HISTORY_LIMIT_MIN = 1
        const val HISTORY_LIMIT_MAX = 100
        const val HISTORY_LIMIT_DEFAULT = 50
    }
}

package com.jun.labs.ledger.service

import java.time.Instant

data class EarnPointsCommand(val userId: Long, val amount: Long, val expiresAt: Instant, val sourceRef: String)

data class UsePointsCommand(val userId: Long, val amount: Long, val orderRef: String)

/** cancelAmount null = 남은 사용액 전부 */
data class CancelUseCommand(val useId: Long, val cancelAmount: Long?)

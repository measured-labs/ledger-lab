package com.jun.labs.ledger.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** ADR-000 §4 규칙 · 설정 */
@ConfigurationProperties("ledger")
data class LedgerProperties(
    val expiry: Expiry = Expiry(),
    val restore: Restore = Restore(),
) {
    data class Expiry(
        val enabled: Boolean = true,
        val interval: Duration = Duration.ofSeconds(60),
        val batchSize: Int = 1000,
    ) {
        init {
            require(batchSize >= 1) { "ledger.expiry.batch-size는 1 이상: $batchSize" }
            require(!interval.isNegative && !interval.isZero) { "ledger.expiry.interval은 0보다 커야: $interval" }
        }
    }

    data class Restore(
        val validDays: Long = 365,
    ) {
        init {
            // 상한이 없으면 요청마다 Duration · Instant 계산이 넘쳐 500이 난다 — 기동에서 막는다
            require(validDays in VALID_DAYS_MIN..VALID_DAYS_MAX) {
                "ledger.restore.valid-days는 $VALID_DAYS_MIN~$VALID_DAYS_MAX: $validDays"
            }
        }

        companion object {
            const val VALID_DAYS_MIN = 1L
            const val VALID_DAYS_MAX = 36_500L
        }
    }
}

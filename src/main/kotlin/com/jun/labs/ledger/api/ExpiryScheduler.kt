package com.jun.labs.ledger.api

import com.jun.labs.ledger.service.ExpirePointsUseCase
import com.jun.labs.ledger.service.LedgerProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 만료 배치 진입점 — ledger.expiry.enabled=false면 등록되지 않는다(테스트는 ExpirePointsUseCase를 직접 부른다) */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "ledger.expiry", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ExpiryScheduler(
    private val expirePointsUseCase: ExpirePointsUseCase,
    private val properties: LedgerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ledger.expiry.interval:60s}", initialDelayString = "\${ledger.expiry.interval:60s}")
    fun run() {
        // 로그는 커밋이 끝난 뒤(트랜잭션 밖)에 남긴다
        val expired = try {
            expirePointsUseCase.expire()
        } catch (e: Exception) {
            log.error("만료 배치 실패 — 이번 실행은 롤백", e)
            return
        }
        if (expired == properties.expiry.batchSize) {
            log.warn("만료 배치가 한도({}건)를 다 채웠다 — 만료 대상이 밀려 있을 수 있다", expired)
        } else if (expired > 0) {
            log.info("만료 배치: {}건 EXPIRED", expired)
        }
    }
}

package com.jun.labs.ledger.impl

import com.jun.labs.ledger.api.ExpiryScheduler
import com.jun.labs.ledger.service.ExpirePointsUseCase
import com.jun.labs.ledger.service.LedgerProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 스케줄러 진입점 — 예외를 밖으로 내지 않고, 커밋 뒤 반환값으로 로그를 남긴다(batch-size 3) */
@ExtendWith(OutputCaptureExtension::class)
class ExpirySchedulerTest {
    private val useCase: ExpirePointsUseCase = mock(ExpirePointsUseCase::class.java)
    private val scheduler = ExpiryScheduler(useCase, LedgerProperties(expiry = LedgerProperties.Expiry(batchSize = 3)))

    @Test
    fun `expire가 예외를 던져도 밖으로 나가지 않고 ERROR 로그`(output: CapturedOutput) {
        `when`(useCase.expire()).thenThrow(IllegalStateException("주입한 실패"))
        scheduler.run()
        verify(useCase, times(1)).expire()
        assertTrue(output.all.contains("ERROR") && output.all.contains("만료 배치 실패"), output.all)
    }

    @Test
    fun `반환값이 batch-size와 같으면 WARN`(output: CapturedOutput) {
        `when`(useCase.expire()).thenReturn(3)
        scheduler.run()
        assertTrue(output.all.contains("WARN") && output.all.contains("한도(3건)"), output.all)
    }

    @Test
    fun `batch-size보다 적으면 INFO만 · WARN 없음`(output: CapturedOutput) {
        `when`(useCase.expire()).thenReturn(2)
        scheduler.run()
        assertTrue(output.all.contains("만료 배치: 2건 EXPIRED"), output.all)
        assertFalse(output.all.contains("WARN"), output.all)
    }

    @Test
    fun `0건이면 로그가 없다`(output: CapturedOutput) {
        `when`(useCase.expire()).thenReturn(0)
        scheduler.run()
        assertFalse(output.all.contains("만료 배치"), output.all)
    }
}

package com.jun.labs.ledger.impl

import com.jun.labs.ledger.domain.PointHistory
import com.jun.labs.ledger.domain.repository.PointHistoryRepository
import com.jun.labs.ledger.service.ExpirePointsUseCase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** 만료 배치 실행 한 번 = 트랜잭션 하나 — 두 번째 건에서 실패하면 첫 번째 건도 원래대로 */
class ExpiryRollbackIntegrationTest : ImplIntegrationTest() {

    @Autowired lateinit var expirePoints: ExpirePointsUseCase

    @MockitoSpyBean lateinit var historyRepository: PointHistoryRepository

    private val t0 = Instant.parse("2500-01-01T00:00:00Z")

    @Test
    fun `두 번째 건 처리 중 예외면 첫 번째 건의 상태 · remaining · 잔액 · 이력이 모두 원래대로`() {
        clock.now = t0
        while (expirePoints.expire() > 0) Unit

        val user = newUserId()
        val first = earn(user, 100, t0.plus(Duration.ofDays(1)))["earnId"].asLong()
        val second = earn(user, 200, t0.plus(Duration.ofDays(2)))["earnId"].asLong()
        val historyBefore = historyRows(user)
        clock.now = t0.plus(Duration.ofDays(3))

        // 리포지토리는 JDK 프록시라 spy가 위임 답(delegatesTo)으로 만들어진다 — callRealMethod 대신 그 기본 답으로 넘긴다
        val delegate = Mockito.mockingDetails(historyRepository).mockCreationSettings.defaultAnswer
        val calls = AtomicInteger()
        val savedRefIds = mutableListOf<Long>()
        doAnswer { invocation ->
            savedRefIds += invocation.getArgument<PointHistory>(0).refId
            if (calls.incrementAndGet() == 2) throw IllegalStateException("두 번째 이력 저장 실패(주입)")
            delegate.answer(invocation)
        }.`when`(historyRepository).save(anyArg())

        assertThrows<IllegalStateException> { expirePoints.expire() }
        assertEquals(2, calls.get())
        // 실패 주입이 이 테스트의 두 건(만료 순 first → second)에서 일어났다 — 다른 클래스 행이 섞였으면 여기서 깨진다
        assertEquals(listOf(first, second), savedRefIds)

        for ((id, remaining) in listOf(first to 100L, second to 200L)) {
            assertEquals("ACTIVE", earnRow(id)["status"])
            assertEquals(remaining, earnRow(id)["remaining"])
        }
        assertEquals(300, balanceColumn(user))
        assertEquals(historyBefore, historyRows(user))
    }
}

/** Kotlin non-null 파라미터에 Mockito 매처를 넘기기 위한 것 — 매처를 등록하고 null을 그대로 돌려준다 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyArg(): T {
    ArgumentMatchers.any<T>()
    return null as T
}

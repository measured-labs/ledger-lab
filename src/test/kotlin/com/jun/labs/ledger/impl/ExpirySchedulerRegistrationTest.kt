package com.jun.labs.ledger.impl

import com.jun.labs.ledger.api.ExpiryScheduler
import com.jun.labs.ledger.service.LedgerProperties
import com.jun.labs.ledger.support.Postgres
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * enabled=true면 스케줄러가 등록되고 운영 기본 interval 형식(`60s`)이 읽힌다.
 * 첫 실행(60초 뒤) 전에 컨텍스트를 닫는다 — 실제 시각으로 공유 DB를 만료시키지 않게.
 */
@SpringBootTest(properties = ["ledger.expiry.enabled=true", "ledger.expiry.interval=60s"])
@DirtiesContext
class ExpirySchedulerRegistrationTest {

    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var properties: LedgerProperties

    @Test
    fun `enabled=true면 스케줄러 빈이 있고 60s 주기 · 첫 지연으로 등록된다`() {
        assertEquals(1, context.getBeansOfType(ExpiryScheduler::class.java).size)
        assertEquals(Duration.ofSeconds(60), properties.expiry.interval)

        val tasks = context.getBeansOfType(ScheduledTaskHolder::class.java).values
            .flatMap { it.scheduledTasks }
            .map { it.task }
            // 6.1+는 runnable을 감싸므로(결과 추적) 메서드 이름으로 찾는다 — 감싼 것의 toString이 ScheduledMethodRunnable의 것을 그대로 쓴다
            .filter { it.runnable.toString().contains("${ExpiryScheduler::class.java.name}.run") }
        assertEquals(1, tasks.size)
        val task = tasks.single() as FixedDelayTask
        assertEquals(Duration.ofSeconds(60), task.intervalDuration)
        assertEquals(Duration.ofSeconds(60), task.initialDelayDuration)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = Postgres.register(registry)
    }
}

/** enabled=false(impl 공통 컨텍스트)면 스케줄러 빈이 없다 */
class ExpirySchedulerDisabledTest : ImplIntegrationTest() {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `enabled=false면 스케줄러 빈이 없다`() {
        assertTrue(context.getBeansOfType(ExpiryScheduler::class.java).isEmpty())
    }
}

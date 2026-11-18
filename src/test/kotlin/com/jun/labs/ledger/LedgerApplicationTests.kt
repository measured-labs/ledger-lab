package com.jun.labs.ledger

import com.jun.labs.ledger.support.Postgres
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

// 만료 스케줄러를 끈다 — 캐시된 컨텍스트의 스케줄러가 실제 시각으로 다른 테스트 클래스의 적립 건을 만료시키지 않게
@SpringBootTest(properties = ["ledger.expiry.enabled=false"])
class LedgerApplicationTests {

    @Test
    fun contextLoads() {
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = Postgres.register(registry)
    }
}

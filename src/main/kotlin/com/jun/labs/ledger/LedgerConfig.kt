package com.jun.labs.ledger

import com.jun.labs.ledger.service.LedgerProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
@EnableConfigurationProperties(LedgerProperties::class)
class LedgerConfig {
    /** 앱은 시각을 이 빈에서만 얻는다. 이름을 `clock`으로 두지 않는다 — 테스트의 @Primary `clock` 빈이 덮어쓸 수 있게 */
    @Bean
    fun systemClock(): Clock = Clock.systemUTC()
}

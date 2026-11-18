package com.jun.labs.ledger.impl

import com.jun.labs.ledger.service.LedgerProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 설정 바인딩 — valid-days 범위 밖이면 기동(바인딩)이 실패하고 메시지에 범위가 있다 */
class LedgerPropertiesTest {

    private fun bind(vararg props: Pair<String, String>): LedgerProperties =
        Binder(MapConfigurationPropertySource(props.toMap())).bindOrCreate("ledger", LedgerProperties::class.java)

    @Test
    fun `기본값과 운영 형식이 바인딩된다`() {
        val p = bind("ledger.expiry.interval" to "60s", "ledger.restore.valid-days" to "365", "ledger.expiry.batch-size" to "1000")
        assertEquals(Duration.ofSeconds(60), p.expiry.interval)
        assertEquals(365, p.restore.validDays)
        assertEquals(1000, p.expiry.batchSize)
        assertEquals(36_500, bind("ledger.restore.valid-days" to "36500").restore.validDays)
        assertEquals(1, bind("ledger.restore.valid-days" to "1").restore.validDays)
    }

    @Test
    fun `valid-days가 0이거나 36500을 넘으면 바인딩 실패 · 메시지에 범위`() {
        for (bad in listOf("0", "36501", "9223372036854775807")) {
            val e = assertThrows<BindException> { bind("ledger.restore.valid-days" to bad) }
            val messages = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue(messages.contains("1~36500"), messages)
        }
    }
}

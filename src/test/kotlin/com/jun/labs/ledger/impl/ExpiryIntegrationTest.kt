package com.jun.labs.ledger.impl

import com.jun.labs.ledger.service.ExpirePointsUseCase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 만료 배치 — 이 컨텍스트는 batch-size 3. DB를 다른 테스트 클래스와 공유하므로 다른 클래스가 쓰지 않는 먼 시각(2400년)에서
 * 먼저 남은 만료 대상을 비우고(drain) 시작한다.
 */
class ExpiryIntegrationTest : ImplIntegrationTest() {

    @Autowired lateinit var expirePoints: ExpirePointsUseCase

    private val t0 = Instant.parse("2400-01-01T00:00:00Z")

    @Test
    fun `배치는 expiresAt 순(같으면 id 순)으로 최대 batch-size건 · 다 쓴 건은 이력 amount 0`() {
        clock.now = t0
        while (expirePoints.expire() > 0) Unit

        val user = newUserId()
        val a = earn(user, 100, t0.plus(Duration.ofDays(1)))["earnId"].asLong()
        val c = earn(user, 300, t0.plus(Duration.ofDays(3)))["earnId"].asLong()
        val b = earn(user, 200, t0.plus(Duration.ofDays(2)))["earnId"].asLong()
        val d = earn(user, 50, t0.plus(Duration.ofDays(3)))["earnId"].asLong()
        val keep = earn(user, 999, t0.plus(Duration.ofDays(100)))["earnId"].asLong()
        assertEquals(201, use(user, 100).status) // a를 다 쓴다(만료 임박 순 첫 건)
        assertEquals(0L, earnRow(a)["remaining"])

        clock.now = t0.plus(Duration.ofDays(5))
        assertEquals(3, expirePoints.expire())
        assertEquals(listOf("EXPIRED", "EXPIRED", "EXPIRED", "ACTIVE", "ACTIVE"), listOf(a, b, c, d, keep).map { earnRow(it)["status"] })
        assertEquals(listOf(0L, 0L, 0L, 50L, 999L), listOf(a, b, c, d, keep).map { earnRow(it)["remaining"] })
        assertEquals(1049, balanceColumn(user))

        assertEquals(1, expirePoints.expire())
        assertEquals("EXPIRED", earnRow(d)["status"])
        assertEquals(0, expirePoints.expire())

        assertEquals(999, balanceColumn(user))
        assertEquals(999, sumActiveRemaining(user))
        val expires = historyRows(user).filter { it["type"] == "EXPIRE" }
        assertEquals(listOf(a, b, c, d), expires.map { it["ref_id"] })
        assertEquals(listOf(0L, 200L, 300L, 50L), expires.map { it["amount"] })
        assertEquals(listOf(1549L, 1349L, 1049L, 999L), expires.map { it["balance_after"] })

        // 만료된 건은 취소할 수 없다
        val r = post("/points/earns/$b/cancel", null)
        assertEquals(409, r.status)
        assertEquals("EARN_NOT_CANCELABLE", r.body["code"].asText())
    }

    @Test
    fun `expiresAt이 now와 같으면 만료 · 1마이크로초 뒤면 아직 아님`() {
        clock.now = t0.plus(Duration.ofDays(1000))
        while (expirePoints.expire() > 0) Unit

        val user = newUserId()
        val at = clock.now.plus(Duration.ofDays(1))
        val e1 = earn(user, 10, at)["earnId"].asLong()
        val e2 = earn(user, 20, at.plusNanos(1000))["earnId"].asLong()
        clock.now = at
        assertEquals(1, expirePoints.expire())
        assertEquals("EXPIRED", earnRow(e1)["status"])
        assertEquals("ACTIVE", earnRow(e2)["status"])
        assertEquals(20, balanceColumn(user))
    }
}

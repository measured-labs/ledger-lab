package com.jun.labs.ledger.impl

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/** 적립 → 여러 적립 건에 걸친 사용(만료 임박 순) → 부분 사용 취소 → 적립 취소 → 남은 사용 취소 — 잔액 · remaining · 이력 대조 */
class PointFlowIntegrationTest : ImplIntegrationTest() {

    private val t0 = Instant.parse("2300-01-01T00:00:00Z")

    @BeforeEach
    fun setClock() {
        clock.now = t0
    }

    @Test
    fun `정상 경로 전체 흐름에서 잔액 = Σ ACTIVE remaining 이고 이력 balance_after가 맞다`() {
        val user = newUserId()
        val e1 = earn(user, 1000, t0.plus(Duration.ofDays(30)))["earnId"].asLong()
        val e2 = earn(user, 500, t0.plus(Duration.ofDays(10)))["earnId"].asLong()
        val e3 = earn(user, 700, t0.plus(Duration.ofDays(10)))["earnId"].asLong()
        assertEquals(2200, get("/points/users/$user/balance").body["balance"].asLong())

        // 사용 900: 만료 임박 순 e2(500) → e3(400) — 같은 만료면 id 순 · e1은 그대로
        clock.advance(Duration.ofMinutes(1))
        val used = use(user, 900)
        assertEquals(201, used.status)
        assertEquals("USED", used.body["status"].asText())
        val useId = used.body["useId"].asLong()
        assertEquals(0L, earnRow(e2)["remaining"])
        assertEquals(300L, earnRow(e3)["remaining"])
        assertEquals(1000L, earnRow(e1)["remaining"])
        assertBalanceConsistent(user, 1300)

        // 부분 사용 취소 300: 새 적립(USE_CANCEL · source_ref = 사용 건 id · now + 365일)
        clock.advance(Duration.ofMinutes(1))
        val partial = post("/points/uses/$useId/cancel", """{"cancelAmount":300}""")
        assertEquals(200, partial.status)
        assertEquals("PARTIALLY_CANCELED", partial.body["status"].asText())
        assertEquals(300, partial.body["canceledAmount"].asLong())
        val restored = jdbc.queryForList(
            "SELECT amount, remaining, expires_at FROM point_earn WHERE user_id = ? AND source = 'USE_CANCEL' AND source_ref = ?",
            user, useId.toString(),
        )
        assertEquals(1, restored.size)
        assertEquals(300L, restored[0]["amount"])
        assertEquals(300L, restored[0]["remaining"])
        assertEquals(clock.now.plus(Duration.ofDays(365)), (restored[0]["expires_at"] as java.sql.Timestamp).toInstant())
        assertBalanceConsistent(user, 1600)

        // 적립 취소 e3(700 중 300 남음): 남은 만큼만 소멸
        val c3 = post("/points/earns/$e3/cancel", null)
        assertEquals(200, c3.status)
        assertEquals("CANCELED", c3.body["status"].asText())
        assertEquals(0, c3.body["remaining"].asLong())
        assertBalanceConsistent(user, 1300)

        // 적립 취소 e2(다 씀): 소멸액 0 · 잔액 그대로
        assertEquals(200, post("/points/earns/$e2/cancel", null).status)
        assertBalanceConsistent(user, 1300)

        // 남은 사용 취소(본문 없음 = 전부) 600
        val rest = post("/points/uses/$useId/cancel", null)
        assertEquals(200, rest.status)
        assertEquals("CANCELED", rest.body["status"].asText())
        assertEquals(900, rest.body["canceledAmount"].asLong())
        assertBalanceConsistent(user, 1900)

        // 이력: 상태 변화 1건 = 1행 · amount는 크기 · balance_after는 변화 뒤 잔액
        val expected = listOf(
            Triple("EARN", 1000L, 1000L), Triple("EARN", 500L, 1500L), Triple("EARN", 700L, 2200L),
            Triple("USE", 900L, 1300L), Triple("USE_CANCEL", 300L, 1600L),
            Triple("EARN_CANCEL", 300L, 1300L), Triple("EARN_CANCEL", 0L, 1300L), Triple("USE_CANCEL", 600L, 1900L),
        )
        val rows = historyRows(user)
        assertEquals(expected, rows.map { Triple(it["type"] as String, it["amount"] as Long, it["balance_after"] as Long) })
        assertEquals(listOf(e1, e2, e3, useId, useId, e3, e2, useId), rows.map { it["ref_id"] as Long })

        // API 이력은 최신순 · limit
        val api = get("/points/users/$user/history?limit=3").body
        assertEquals(3, api.size())
        assertEquals(listOf("USE_CANCEL", "EARN_CANCEL", "EARN_CANCEL"), api.map { it["type"].asText() })
        assertEquals(1900, api[0]["balanceAfter"].asLong())
        assertEquals(8, get("/points/users/$user/history").body.size())

        // 조회 API
        val gotUse = get("/points/uses/$useId")
        assertEquals(200, gotUse.status)
        assertEquals(900, gotUse.body["amount"].asLong())
        val gotEarn = get("/points/earns/$e1")
        assertEquals(1000, gotEarn.body["remaining"].asLong())
        assertEquals("EARN", gotEarn.body["source"].asText())
        assertEquals(t0.plus(Duration.ofDays(30)), Instant.parse(gotEarn.body["expiresAt"].asText()))
    }

    @Test
    fun `만료 시각이 지난 적립 건은 배치 전에도 사용 대상에서 빠지고 잔액은 남는다`() {
        val user = newUserId()
        earn(user, 100, t0.plus(Duration.ofDays(1)))
        val e2 = earn(user, 50, t0.plus(Duration.ofDays(5)))["earnId"].asLong()
        clock.now = t0.plus(Duration.ofDays(2))

        // 잔액 150 ≥ 120 이지만 살아 있는 적립 건 합 50 → 409 · 상태 불변
        val r = use(user, 120)
        assertEquals(409, r.status)
        assertEquals("INSUFFICIENT_BALANCE", r.body["code"].asText())
        assertEquals(150, balanceColumn(user))
        assertEquals(50L, earnRow(e2)["remaining"])
        assertEquals(0, count("point_use", user))
        assertEquals(2, count("point_history", user))

        // 살아 있는 건 범위 안이면 통과
        assertEquals(201, use(user, 50).status)
        assertEquals(100, balanceColumn(user))
    }

    @Test
    fun `금액 1~100,000,000 경계는 통과한다`() {
        val user = newUserId()
        earn(user, 100_000_000, t0.plusSeconds(1))
        earn(user, 1, t0.plusSeconds(2))
        assertEquals(201, use(user, 100_000_000).status)
        assertEquals(1, balanceColumn(user))
    }

    @Test
    fun `같은 사용자 지갑이 둘이면 가장 작은 id를 쓴다`() {
        val user = newUserId()
        earn(user, 100, t0.plus(Duration.ofDays(1)))
        jdbc.update("INSERT INTO point_wallet(user_id, balance, updated_at) VALUES (?, 999, now())", user)
        assertEquals(100, get("/points/users/$user/balance").body["balance"].asLong())
        earn(user, 10, t0.plus(Duration.ofDays(1)))
        assertEquals(
            listOf(110L, 999L),
            jdbc.queryForList("SELECT balance FROM point_wallet WHERE user_id = ? ORDER BY id", Long::class.java, user),
        )
    }

    private fun assertBalanceConsistent(user: Long, expected: Long) {
        assertEquals(expected, balanceColumn(user), "잔액 컬럼")
        assertEquals(expected, sumActiveRemaining(user), "Σ ACTIVE remaining")
        assertEquals(expected, get("/points/users/$user/balance").body["balance"].asLong(), "잔액 API")
    }
}

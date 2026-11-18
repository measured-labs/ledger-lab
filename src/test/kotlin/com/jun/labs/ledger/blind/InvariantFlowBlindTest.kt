package com.jun.labs.ledger.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 정상 경로 불변식 — 명세 §2 · ADR-000 §2.1 ①~⑤.
 * 각 단계 뒤: 잔액 = Σ ACTIVE remaining(배치 전 창 포함) · 이력 1행 = 변화 1건 · 이력 balance_after = 그 시점 잔액.
 */
class InvariantFlowBlindTest : BlindTestSupport() {

    @Test
    fun `적립 → 사용 → 부분 사용 취소 → 적립 취소 → 배치 전 창 → 만료 배치 - 단계마다 불변식`() {
        drainExpiry()
        val userId = newUserId()
        val balances = mutableListOf<Long>()
        val types = mutableListOf<String>()

        fun step(type: String, expectedBalance: Long) {
            types += type
            balances += expectedBalance
            assertThat(balanceOf(userId)).describedAs("$type 뒤 잔액").isEqualTo(expectedBalance)
            assertInvariant(userId)
            val rows = historyRows(userId)
            assertThat(rows).describedAs("이력 1행 = 변화 1건").hasSize(types.size)
            assertThat(rows.map { it.str("type") }).containsExactlyElementsOf(types)
            assertThat(rows.map { it.long("balance_after") }).containsExactlyElementsOf(balances)
        }

        val soon = earnOk(userId, 1_000L, Duration.ofSeconds(30))["earnId"].asLong()
        step("EARN", 1_000L)
        val later = earnOk(userId, 500L, Duration.ofDays(10))["earnId"].asLong()
        step("EARN", 1_500L)

        val useId = useOk(userId, 1_200L)["useId"].asLong()
        step("USE", 300L)
        assertThat(earnRow(soon).long("remaining")).isEqualTo(0L)
        assertThat(earnRow(later).long("remaining")).isEqualTo(300L)

        assertThat(cancelUse(useId, json("cancelAmount" to 400L)).status).isEqualTo(200)
        step("USE_CANCEL", 700L)

        assertThat(cancelEarn(later).status).isEqualTo(200)
        step("EARN_CANCEL", 400L) // later 의 남은 300 만 소멸

        val tiny = earnOk(userId, 50L, Duration.ofSeconds(31))["earnId"].asLong()
        step("EARN", 450L)

        clock.advance(Duration.ofSeconds(40))
        // 배치 전 창: soon(0)과 tiny(50) 모두 만료됐지만 아직 ACTIVE — 잔액에 남아 있고 사용 대상에서는 빠진다
        assertThat(balanceOf(userId)).isEqualTo(450L)
        assertInvariant(userId)
        assertError(use(userId, 401L), 409, "INSUFFICIENT_BALANCE")
        assertThat(historyRows(userId)).hasSize(types.size)

        assertThat(expirableIds(Int.MAX_VALUE)).contains(soon, tiny)
        expireUntilDone(listOf(soon, tiny)) // soon(다 씀 → amount 0) · tiny(50)
        types += "EXPIRE"; balances += 450L
        types += "EXPIRE"; balances += 400L
        assertThat(historyRows(userId).map { it.str("type") }).containsExactlyElementsOf(types)
        assertThat(historyRows(userId).map { it.long("balance_after") }).containsExactlyElementsOf(balances)
        assertThat(historyRows(userId).takeLast(2).map { it.long("amount") }).containsExactly(0L, 50L)
        assertThat(earnRow(soon).str("status")).isEqualTo("EXPIRED")
        assertThat(earnRow(tiny).str("status")).isEqualTo("EXPIRED")
        assertThat(balanceOf(userId)).isEqualTo(400L)
        assertInvariant(userId)

        // 남은 400 = 환원 적립 1건
        val restored = earnRows(userId).filter { it.str("status") == "ACTIVE" }
        assertThat(restored).hasSize(1)
        assertThat(restored.single().str("source")).isEqualTo("USE_CANCEL")
        assertThat(restored.single().long("remaining")).isEqualTo(400L)
    }
}

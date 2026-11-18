package com.jun.labs.ledger.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** 사용 — ADR-000 §3 사용 절차(잔액 컬럼 선검사 → 만료 임박 순 차감 → 모자라면 롤백) · §6 사용 건 응답. */
class UseBlindTest : BlindTestSupport() {

    @Test
    fun `사용 - 201, 응답 필드, 잔액 감소, 사용 건 행, 이력 USE`() {
        val userId = newUserId()
        earnOk(userId, 1_000L)

        val res = use(userId, 400L, "order-77")

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        val body = res.json
        val useId = body["useId"].asLong()
        assertThat(useId).isPositive()
        assertThat(body["userId"].asLong()).isEqualTo(userId)
        assertThat(body["amount"].asLong()).isEqualTo(400L)
        assertThat(body["canceledAmount"].asLong()).isEqualTo(0L)
        assertThat(body.textOrNull("orderRef")).isEqualTo("order-77")
        assertThat(body.textOrNull("status")).isEqualTo("USED")
        assertThat(body.instant("usedAt")).isEqualTo(now())

        assertThat(balanceOf(userId)).isEqualTo(600L)
        val row = useRow(useId)
        assertThat(row.long("amount")).isEqualTo(400L)
        assertThat(row.long("canceled_amount")).isEqualTo(0L)
        assertThat(row.str("status")).isEqualTo("USED")
        assertThat(row.str("order_ref")).isEqualTo("order-77")

        val last = historyRows(userId).last()
        assertThat(last.str("type")).isEqualTo("USE")
        assertThat(last.long("amount")).isEqualTo(400L)
        assertThat(last.long("ref_id")).isEqualTo(useId)
        assertThat(last.long("balance_after")).isEqualTo(600L)
        assertInvariant(userId)
    }

    @Test
    fun `여러 적립 건에 걸쳐 만료 임박 순으로 깎고 만료 시각이 같으면 id 순`() {
        val userId = newUserId()
        val e1 = earnOk(userId, 100L, Duration.ofDays(10))["earnId"].asLong()
        val e2 = earnOk(userId, 100L, Duration.ofDays(5))["earnId"].asLong()
        val e3 = earnOk(userId, 100L, Duration.ofDays(5))["earnId"].asLong()
        val e4 = earnOk(userId, 100L, Duration.ofDays(20))["earnId"].asLong()

        useOk(userId, 250L)

        assertThat(earnRow(e2).long("remaining")).describedAs("만료 가장 임박 · 작은 id").isEqualTo(0L)
        assertThat(earnRow(e3).long("remaining")).describedAs("만료 같음 · 큰 id").isEqualTo(0L)
        assertThat(earnRow(e1).long("remaining")).describedAs("다음 임박").isEqualTo(50L)
        assertThat(earnRow(e4).long("remaining")).describedAs("가장 늦게 만료").isEqualTo(100L)
        listOf(e1, e2, e3, e4).forEach { assertThat(earnRow(it).str("status")).isEqualTo("ACTIVE") }
        assertThat(balanceOf(userId)).isEqualTo(150L)
        assertInvariant(userId)
    }

    @Test
    fun `같은 만료 시각 - id 작은 건이 먼저 깎인다`() {
        val userId = newUserId()
        val expiresAt = inFuture(Duration.ofDays(7))
        val first = earn(userId, 100L, expiresAt).also { assertThat(it.status).isEqualTo(201) }.json["earnId"].asLong()
        val second = earn(userId, 100L, expiresAt).also { assertThat(it.status).isEqualTo(201) }.json["earnId"].asLong()

        useOk(userId, 30L)

        assertThat(earnRow(first).long("remaining")).isEqualTo(70L)
        assertThat(earnRow(second).long("remaining")).isEqualTo(100L)
    }

    @Test
    fun `잔액 전부 사용 - 잔액 0, 모든 적립 건 remaining 0 이지만 ACTIVE 유지`() {
        val userId = newUserId()
        val a = earnOk(userId, 300L)["earnId"].asLong()
        val b = earnOk(userId, 200L, Duration.ofDays(60))["earnId"].asLong()

        useOk(userId, 500L)

        assertThat(balanceOf(userId)).isEqualTo(0L)
        assertThat(earnRow(a).long("remaining")).isEqualTo(0L)
        assertThat(earnRow(b).long("remaining")).isEqualTo(0L)
        assertThat(earnRow(a).str("status")).isEqualTo("ACTIVE")
        assertInvariant(userId)
    }

    @Test
    fun `잔액 부족 - 409 INSUFFICIENT_BALANCE, 상태 불변`() {
        val userId = newUserId()
        earnOk(userId, 100L)
        val before = snapshot(userId)

        val res = use(userId, 101L)

        assertError(res, 409, "INSUFFICIENT_BALANCE")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @Test
    fun `지갑 없음 - 409 INSUFFICIENT_BALANCE, 지갑 · 사용 건 · 이력 생기지 않음`() {
        val userId = newUserId()

        val res = use(userId, 1L)

        assertError(res, 409, "INSUFFICIENT_BALANCE")
        assertThat(snapshot(userId)).isEqualTo(UserSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `만료됐지만 배치 전인 적립 건은 사용 대상에서 빠진다 - 잔액은 충분한데 409, 롤백`() {
        val userId = newUserId()
        val expiring = earnOk(userId, 100L, Duration.ofSeconds(1))["earnId"].asLong()
        val alive = earnOk(userId, 50L, Duration.ofDays(1))["earnId"].asLong()
        clock.advance(Duration.ofSeconds(2))

        // 배치 전 창: 잔액 컬럼에는 만료 건의 remaining이 남아 있다
        assertThat(balanceOf(userId)).isEqualTo(150L)
        assertInvariant(userId)
        val before = snapshot(userId)

        val res = use(userId, 100L)

        assertError(res, 409, "INSUFFICIENT_BALANCE")
        assertThat(snapshot(userId)).describedAs("깎은 합이 모자라면 롤백 — alive 건 remaining도 그대로").isEqualTo(before)

        // 사용 가능한 건(alive)만큼은 쓸 수 있고 만료 건은 건드리지 않는다
        useOk(userId, 50L)
        assertThat(earnRow(alive).long("remaining")).isEqualTo(0L)
        assertThat(earnRow(expiring).long("remaining")).isEqualTo(100L)
        assertThat(earnRow(expiring).str("status")).isEqualTo("ACTIVE")
        assertThat(balanceOf(userId)).isEqualTo(100L)
        assertInvariant(userId)
    }

    @Test
    fun `만료 시각이 정확히 지금인 건도 사용 대상에서 빠진다`() {
        val userId = newUserId()
        val exact = earnOk(userId, 100L, Duration.ofSeconds(10))["earnId"].asLong()
        clock.advance(Duration.ofSeconds(10))

        val res = use(userId, 1L)

        assertError(res, 409, "INSUFFICIENT_BALANCE")
        assertThat(earnRow(exact).long("remaining")).isEqualTo(100L)
        assertThat(balanceOf(userId)).isEqualTo(100L)
    }

    @Test
    fun `취소된 적립 건은 사용 대상이 아니다`() {
        val userId = newUserId()
        val canceled = earnOk(userId, 100L, Duration.ofDays(1))["earnId"].asLong()
        val other = earnOk(userId, 100L, Duration.ofDays(9))["earnId"].asLong()
        assertThat(cancelEarn(canceled).status).isEqualTo(200)

        useOk(userId, 60L)

        assertThat(earnRow(canceled).long("remaining")).isEqualTo(0L)
        assertThat(earnRow(other).long("remaining")).isEqualTo(40L)
        assertInvariant(userId)
    }
}

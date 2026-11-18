package com.jun.labs.ledger.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** 적립 — ADR-000 §3 적립 절차 · §6 적립 건 응답 · §2 지갑 lazy 생성. */
class EarnBlindTest : BlindTestSupport() {

    @Test
    fun `적립 - 201, 응답 필드, remaining = amount, ACTIVE, source EARN`() {
        val userId = newUserId()
        val expiresAt = now().plus(Duration.ofDays(30))

        val res = earn(userId, 1_500L, iso(expiresAt), "promo-1")

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        val body = res.json
        assertThat(body["earnId"].asLong()).isPositive()
        assertThat(body["userId"].asLong()).isEqualTo(userId)
        assertThat(body["amount"].asLong()).isEqualTo(1_500L)
        assertThat(body["remaining"].asLong()).isEqualTo(1_500L)
        assertThat(body.textOrNull("source")).isEqualTo("EARN")
        assertThat(body.textOrNull("sourceRef")).isEqualTo("promo-1")
        assertThat(body.textOrNull("status")).isEqualTo("ACTIVE")
        assertThat(body.instant("expiresAt")).isEqualTo(expiresAt)
        assertThat(body.instant("createdAt")).describedAs("시각은 주입된 Clock에서").isEqualTo(now())

        val row = earnRow(body["earnId"].asLong())
        assertThat(row.long("user_id")).isEqualTo(userId)
        assertThat(row.long("amount")).isEqualTo(1_500L)
        assertThat(row.long("remaining")).isEqualTo(1_500L)
        assertThat(row.str("source")).isEqualTo("EARN")
        assertThat(row.str("source_ref")).isEqualTo("promo-1")
        assertThat(row.str("status")).isEqualTo("ACTIVE")
        assertThat(row.time("expires_at")).isEqualTo(expiresAt)
    }

    @Test
    fun `첫 적립이 지갑을 만들고 두 번째 적립은 같은 지갑을 쓴다`() {
        val userId = newUserId()
        assertThat(walletRows(userId)).isEmpty()

        earnOk(userId, 1_000L)
        assertThat(walletRows(userId)).hasSize(1)
        assertThat(walletRows(userId).single().long("balance")).isEqualTo(1_000L)

        earnOk(userId, 250L)
        assertThat(walletRows(userId)).hasSize(1)
        assertThat(walletRows(userId).single().long("balance")).isEqualTo(1_250L)
        assertThat(balanceOf(userId)).isEqualTo(1_250L)
        assertInvariant(userId)
    }

    @Test
    fun `적립마다 이력 EARN 1행 - amount, refId = earnId, balance_after = 그 시점 잔액`() {
        val userId = newUserId()
        val first = earnOk(userId, 1_000L)
        val second = earnOk(userId, 300L)

        val rows = historyRows(userId)
        assertThat(rows).hasSize(2)
        assertThat(rows[0].str("type")).isEqualTo("EARN")
        assertThat(rows[0].long("amount")).isEqualTo(1_000L)
        assertThat(rows[0].long("ref_id")).isEqualTo(first["earnId"].asLong())
        assertThat(rows[0].long("balance_after")).isEqualTo(1_000L)
        assertThat(rows[1].str("type")).isEqualTo("EARN")
        assertThat(rows[1].long("amount")).isEqualTo(300L)
        assertThat(rows[1].long("ref_id")).isEqualTo(second["earnId"].asLong())
        assertThat(rows[1].long("balance_after")).isEqualTo(1_300L)
    }

    @Test
    fun `경계 값 - amount 100,000,000 과 sourceRef 64자는 허용`() {
        val userId = newUserId()
        val ref = "r".repeat(64)

        val res = earn(userId, 100_000_000L, inFuture(Duration.ofSeconds(1)), ref)

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertThat(res.json.textOrNull("sourceRef")).isEqualTo(ref)
        assertThat(balanceOf(userId)).isEqualTo(100_000_000L)
    }

    @Test
    fun `expiresAt 오프셋 표기도 ISO-8601이면 받는다`() {
        val userId = newUserId()
        val expiresAt = now().plus(Duration.ofDays(3))
        val withOffset = expiresAt.atOffset(java.time.ZoneOffset.ofHours(9)).toString()

        val res = earn(userId, 10L, withOffset)

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertThat(res.json.instant("expiresAt")).isEqualTo(expiresAt)
    }
}

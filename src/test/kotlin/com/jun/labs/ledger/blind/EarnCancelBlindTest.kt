package com.jun.labs.ledger.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** 적립 취소 — ADR-000 §3 PointEarn ACTIVE → CANCELED · 남은 만큼만 소멸 · 쓴 몫 미회수 · 다 쓴 건 amount 0 이력. */
class EarnCancelBlindTest : BlindTestSupport() {

    @Test
    fun `쓰지 않은 적립 취소 - 200 CANCELED, remaining 0, 잔액 감소, 이력 EARN_CANCEL`() {
        val userId = newUserId()
        val earnId = earnOk(userId, 800L, sourceRef = "c-1")["earnId"].asLong()
        earnOk(userId, 200L)

        val res = cancelEarn(earnId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json["earnId"].asLong()).isEqualTo(earnId)
        assertThat(res.json["amount"].asLong()).isEqualTo(800L)
        assertThat(res.json["remaining"].asLong()).isEqualTo(0L)
        assertThat(res.json.textOrNull("status")).isEqualTo("CANCELED")
        assertThat(res.json.textOrNull("sourceRef")).isEqualTo("c-1")
        assertThat(earnRow(earnId).str("status")).isEqualTo("CANCELED")
        assertThat(earnRow(earnId).long("remaining")).isEqualTo(0L)
        assertThat(balanceOf(userId)).isEqualTo(200L)

        val last = historyRows(userId).last()
        assertThat(last.str("type")).isEqualTo("EARN_CANCEL")
        assertThat(last.long("amount")).isEqualTo(800L)
        assertThat(last.long("ref_id")).isEqualTo(earnId)
        assertThat(last.long("balance_after")).isEqualTo(200L)
        assertInvariant(userId)
    }

    @Test
    fun `일부 쓴 적립 취소 - 남은 만큼만 소멸하고 쓴 몫은 회수하지 않는다`() {
        val userId = newUserId()
        val earnId = earnOk(userId, 1_000L)["earnId"].asLong()
        val useId = useOk(userId, 300L)["useId"].asLong()
        val useBefore = useRow(useId)

        val res = cancelEarn(earnId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json["remaining"].asLong()).isEqualTo(0L)
        assertThat(balanceOf(userId)).isEqualTo(0L)
        assertThat(useRow(useId)).describedAs("사용 건은 그대로").isEqualTo(useBefore)
        val last = historyRows(userId).last()
        assertThat(last.str("type")).isEqualTo("EARN_CANCEL")
        assertThat(last.long("amount")).isEqualTo(700L)
        assertThat(last.long("balance_after")).isEqualTo(0L)
        assertInvariant(userId)
    }

    @Test
    fun `다 쓴 적립 취소 - CANCELED, 잔액 그대로, 이력 amount 0`() {
        val userId = newUserId()
        val spent = earnOk(userId, 500L, Duration.ofDays(1))["earnId"].asLong()
        earnOk(userId, 300L, Duration.ofDays(9))
        useOk(userId, 500L)
        assertThat(earnRow(spent).long("remaining")).isEqualTo(0L)
        val historyBefore = historyRows(userId).size

        val res = cancelEarn(spent)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.textOrNull("status")).isEqualTo("CANCELED")
        assertThat(balanceOf(userId)).isEqualTo(300L)
        val rows = historyRows(userId)
        assertThat(rows).hasSize(historyBefore + 1)
        assertThat(rows.last().str("type")).isEqualTo("EARN_CANCEL")
        assertThat(rows.last().long("amount")).isEqualTo(0L)
        assertThat(rows.last().long("ref_id")).isEqualTo(spent)
        assertThat(rows.last().long("balance_after")).isEqualTo(300L)
        assertInvariant(userId)
    }

    @Test
    fun `CANCELED 적립 재취소 - 409 EARN_NOT_CANCELABLE, 상태 불변`() {
        val userId = newUserId()
        val earnId = earnOk(userId, 100L)["earnId"].asLong()
        assertThat(cancelEarn(earnId).status).isEqualTo(200)
        val before = snapshot(userId)

        assertError(cancelEarn(earnId), 409, "EARN_NOT_CANCELABLE")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @Test
    fun `EXPIRED 적립 취소 - 409 EARN_NOT_CANCELABLE, 상태 불변`() {
        val userId = newUserId()
        val earnId = earnOk(userId, 100L, Duration.ofSeconds(1))["earnId"].asLong()
        clock.advance(Duration.ofSeconds(2))
        drainExpiry()
        assertThat(earnRow(earnId).str("status")).isEqualTo("EXPIRED")
        val before = snapshot(userId)

        assertError(cancelEarn(earnId), 409, "EARN_NOT_CANCELABLE")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @Test
    fun `만료됐지만 배치 전(아직 ACTIVE)인 적립은 취소된다 - 남은 만큼 소멸`() {
        val userId = newUserId()
        val earnId = earnOk(userId, 100L, Duration.ofSeconds(1))["earnId"].asLong()
        clock.advance(Duration.ofSeconds(2))

        val res = cancelEarn(earnId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.textOrNull("status")).isEqualTo("CANCELED")
        assertThat(balanceOf(userId)).isEqualTo(0L)
        assertInvariant(userId)
    }

    @Test
    fun `없는 적립 건 - 404 EARN_NOT_FOUND`() {
        assertError(cancelEarn(Long.MAX_VALUE), 404, "EARN_NOT_FOUND")
    }

    @Test
    fun `적립 취소는 본문을 읽지 않는다 - 깨진 본문이 있어도 취소된다`() {
        val userId = newUserId()
        val earnId = earnOk(userId, 100L)["earnId"].asLong()

        val res = cancelEarn(earnId, "{not json")

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.textOrNull("status")).isEqualTo("CANCELED")
    }
}

package com.jun.labs.ledger.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.stream.Stream

/** 사용 취소 — ADR-000 §3 PointUse 상태 전이 · 사용 취소 절차(새 적립 USE_CANCEL · now + 365일) · §6 · test-contract §3. */
class UseCancelBlindTest : BlindTestSupport() {

    private fun setup(earn: Long = 1_000L, use: Long = 600L): Pair<Long, Long> {
        val userId = newUserId()
        earnOk(userId, earn)
        val useId = useOk(userId, use)["useId"].asLong()
        return userId to useId
    }

    private fun restoreEarns(userId: Long) = earnRows(userId).filter { it.str("source") == "USE_CANCEL" }

    @Test
    fun `부분 취소 - 200 PARTIALLY_CANCELED, 새 적립 USE_CANCEL, 잔액 증가, 이력`() {
        val (userId, useId) = setup()

        val res = cancelUse(useId, json("cancelAmount" to 200L))

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json["useId"].asLong()).isEqualTo(useId)
        assertThat(res.json["amount"].asLong()).isEqualTo(600L)
        assertThat(res.json["canceledAmount"].asLong()).isEqualTo(200L)
        assertThat(res.json.textOrNull("status")).isEqualTo("PARTIALLY_CANCELED")
        assertThat(useRow(useId).long("canceled_amount")).isEqualTo(200L)
        assertThat(useRow(useId).str("status")).isEqualTo("PARTIALLY_CANCELED")

        val restored = restoreEarns(userId)
        assertThat(restored).hasSize(1)
        val r = restored.single()
        assertThat(r.long("amount")).isEqualTo(200L)
        assertThat(r.long("remaining")).isEqualTo(200L)
        assertThat(r.str("status")).isEqualTo("ACTIVE")
        assertThat(r.str("source_ref")).isEqualTo(useId.toString())
        assertThat(r.time("expires_at")).isEqualTo(now().plus(Duration.ofDays(365)))

        assertThat(balanceOf(userId)).isEqualTo(600L)
        val last = historyRows(userId).last()
        assertThat(last.str("type")).isEqualTo("USE_CANCEL")
        assertThat(last.long("amount")).isEqualTo(200L)
        assertThat(last.long("ref_id")).isEqualTo(useId) // ADR-000 §3 — USE_CANCEL 이력은 사용 건 id
        assertThat(last.long("balance_after")).isEqualTo(600L)
        assertThat(historyRows(userId)).hasSize(3)
        assertInvariant(userId)
    }

    @Test
    fun `부분 취소 두 번 뒤 남은 전부 취소 - CANCELED, canceledAmount = amount, 환원 적립 3건`() {
        val (userId, useId) = setup()

        assertThat(cancelUse(useId, json("cancelAmount" to 100L)).json.textOrNull("status")).isEqualTo("PARTIALLY_CANCELED")
        assertThat(cancelUse(useId, json("cancelAmount" to 100L)).json.textOrNull("status")).isEqualTo("PARTIALLY_CANCELED")
        val res = cancelUse(useId, json("cancelAmount" to 400L))

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.textOrNull("status")).isEqualTo("CANCELED")
        assertThat(res.json["canceledAmount"].asLong()).isEqualTo(600L)
        assertThat(restoreEarns(userId).map { it.long("amount") }).containsExactly(100L, 100L, 400L)
        assertThat(balanceOf(userId)).isEqualTo(1_000L)
        assertInvariant(userId)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fullCancelBodies")
    fun `본문 생략 · {} · cancelAmount null 은 남은 사용액 전부 취소`(label: String, body: String?) {
        val (userId, useId) = setup(earn = 1_000L, use = 600L)
        assertThat(cancelUse(useId, json("cancelAmount" to 150L)).status).isEqualTo(200)

        val res = cancelUse(useId, body)

        assertThat(res.status).describedAs("$label → ${res.body}").isEqualTo(200)
        assertThat(res.json.textOrNull("status")).isEqualTo("CANCELED")
        assertThat(res.json["canceledAmount"].asLong()).isEqualTo(600L)
        assertThat(restoreEarns(userId).map { it.long("amount") }).containsExactly(150L, 450L)
        assertThat(balanceOf(userId)).isEqualTo(1_000L)
        assertInvariant(userId)
    }

    @Test
    fun `사용 금액 전부를 한 번에 취소 - USED에서 바로 CANCELED`() {
        val (userId, useId) = setup(earn = 500L, use = 500L)

        val res = cancelUse(useId, json("cancelAmount" to 500L))

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.textOrNull("status")).isEqualTo("CANCELED")
        assertThat(balanceOf(userId)).isEqualTo(500L)
    }

    @Test
    fun `남은 사용액 초과 - 409 CANCEL_AMOUNT_EXCEEDED, 상태 불변`() {
        val (userId, useId) = setup(earn = 1_000L, use = 600L)
        assertThat(cancelUse(useId, json("cancelAmount" to 200L)).status).isEqualTo(200)
        val before = snapshot(userId)

        val res = cancelUse(useId, json("cancelAmount" to 401L))

        assertError(res, 409, "CANCEL_AMOUNT_EXCEEDED")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @Test
    fun `CANCELED 사용 건 재취소 - 409 USE_NOT_CANCELABLE, 상태 불변`() {
        val (userId, useId) = setup()
        assertThat(cancelUse(useId, "{}").status).isEqualTo(200)
        val before = snapshot(userId)

        assertError(cancelUse(useId, "{}"), 409, "USE_NOT_CANCELABLE")
        assertError(cancelUse(useId, json("cancelAmount" to 1L)), 409, "USE_NOT_CANCELABLE")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @Test
    fun `없는 사용 건 - 404 USE_NOT_FOUND`() {
        assertError(cancelUse(Long.MAX_VALUE, "{}"), 404, "USE_NOT_FOUND")
    }

    @Test
    fun `환원 적립은 원래 적립 건의 유효기간과 무관하게 지금부터 365일 - 이미 만료된 적립에서 쓴 몫도 되살아난다`() {
        val userId = newUserId()
        val original = earnOk(userId, 100L, Duration.ofSeconds(1))["earnId"].asLong()
        val useId = useOk(userId, 100L)["useId"].asLong()
        clock.advance(Duration.ofSeconds(5))
        drainExpiry()
        assertThat(earnRow(original).str("status")).isEqualTo("EXPIRED")
        assertThat(balanceOf(userId)).isEqualTo(0L)

        val res = cancelUse(useId, null)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        val restored = restoreEarns(userId).single()
        assertThat(restored.long("remaining")).isEqualTo(100L)
        assertThat(restored.time("expires_at")).isEqualTo(now().plus(Duration.ofDays(365)))
        assertThat(earnRow(original).str("status")).describedAs("원래 적립 건은 되살아나지 않는다").isEqualTo("EXPIRED")
        assertThat(balanceOf(userId)).isEqualTo(100L)
        assertInvariant(userId)
    }

    @Test
    fun `환원 적립은 다시 사용할 수 있다`() {
        val (userId, useId) = setup(earn = 300L, use = 300L)
        assertThat(cancelUse(useId, json("cancelAmount" to 120L)).status).isEqualTo(200)

        useOk(userId, 120L)

        assertThat(restoreEarns(userId).single().long("remaining")).isEqualTo(0L)
        assertThat(balanceOf(userId)).isEqualTo(0L)
        assertInvariant(userId)
    }

    companion object {
        @JvmStatic
        fun fullCancelBodies(): Stream<Arguments> = Stream.of(
            Arguments.of("본문 없음", null),
            Arguments.of("{}", "{}"),
            Arguments.of("cancelAmount null", """{"cancelAmount": null}"""),
        )
    }
}

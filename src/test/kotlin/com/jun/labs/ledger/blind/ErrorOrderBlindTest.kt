package com.jun.labs.ledger.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 판정 순서와 오류 본문 — ADR-000 §6.1 판정 순서(입력 400 → 존재 404 → 상태 409 NOT_CANCELABLE → 금액 409) ·
 * §6 오류 본문 {code, message} · test-contract §3(Spring 표준 예외 = HTTP_<상태>).
 */
class ErrorOrderBlindTest : BlindTestSupport() {

    private fun canceledUse(): Pair<Long, Long> {
        val userId = newUserId()
        earnOk(userId, 500L)
        val useId = useOk(userId, 200L)["useId"].asLong()
        assertThat(cancelUse(useId, "{}").status).isEqualTo(200)
        return userId to useId
    }

    @Test
    fun `입력이 존재보다 먼저 - 없는 사용 건에 cancelAmount 0 은 400`() {
        assertError(cancelUse(Long.MAX_VALUE, json("cancelAmount" to 0L)), 400, "INVALID_REQUEST")
    }

    @Test
    fun `입력이 존재보다 먼저 - 잘못된 경로 id 와 잘못된 본문이 함께면 400`() {
        assertError(cancelUse("abc", json("cancelAmount" to 1.5)), 400, "INVALID_REQUEST")
    }

    @Test
    fun `존재가 금액보다 먼저 - 없는 사용 건에 아주 큰(허용 범위) cancelAmount 는 404`() {
        assertError(cancelUse(Long.MAX_VALUE, json("cancelAmount" to 100_000_000L)), 404, "USE_NOT_FOUND")
    }

    @Test
    fun `입력이 상태보다 먼저 - CANCELED 사용 건에 cancelAmount 0 은 400`() {
        val (userId, useId) = canceledUse()
        val before = snapshot(userId)
        assertError(cancelUse(useId, json("cancelAmount" to 0L)), 400, "INVALID_REQUEST")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @Test
    fun `상태가 금액보다 먼저 - CANCELED 사용 건에 초과 cancelAmount 는 USE_NOT_CANCELABLE`() {
        val (userId, useId) = canceledUse()
        val before = snapshot(userId)
        assertError(cancelUse(useId, json("cancelAmount" to 99_999L)), 409, "USE_NOT_CANCELABLE")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @Test
    fun `입력이 금액보다 먼저 - 잔액 부족이면서 orderRef 공백이면 400`() {
        val userId = newUserId()
        earnOk(userId, 10L)
        assertError(use(userId, 1_000L, "   "), 400, "INVALID_REQUEST")
    }

    @Test
    fun `입력이 금액보다 먼저 - 지갑 없는 사용자의 100,000,001 사용은 400`() {
        val userId = newUserId()
        assertError(use(userId, 100_000_001L), 400, "INVALID_REQUEST")
        assertThat(walletRows(userId)).isEmpty()
    }

    @Test
    fun `적립 취소 - 잘못된 경로 id 는 400, 없는 id 는 404, CANCELED 는 409`() {
        assertError(cancelEarn("-1"), 400, "INVALID_REQUEST")
        assertError(cancelEarn(Long.MAX_VALUE), 404, "EARN_NOT_FOUND")
        val userId = newUserId()
        val earnId = earnOk(userId, 10L)["earnId"].asLong()
        assertThat(cancelEarn(earnId).status).isEqualTo(200)
        assertError(cancelEarn(earnId), 409, "EARN_NOT_CANCELABLE")
    }

    @Test
    fun `없는 경로 - 404 HTTP_404 {code, message}`() {
        assertError(app.get("/points/nowhere"), 404, "HTTP_404")
        assertError(app.post("/no/such/path", "{}"), 404, "HTTP_404")
    }

    @Test
    fun `허용되지 않는 메서드 - 405 HTTP_405, Allow 헤더 유지`() {
        val res = app.get("/points/earn")
        assertError(res, 405, "HTTP_405")
        assertThat(res.header("Allow")).describedAs("Allow 헤더").isNotNull().contains("POST")

        val del = app.send("DELETE", "/points/earns/1", null)
        assertError(del, 405, "HTTP_405")
        assertThat(del.header("Allow")).isNotNull().contains("GET")
    }

    @Test
    fun `JSON 이 아닌 Content-Type - 415 HTTP_415`() {
        val userId = newUserId()
        val res = app.post("/points/earn", """{"userId":$userId,"amount":1,"expiresAt":"${inFuture(java.time.Duration.ofDays(1))}","sourceRef":"x"}""", "text/plain")
        assertError(res, 415, "HTTP_415")
        assertThat(walletRows(userId)).isEmpty()
    }
}

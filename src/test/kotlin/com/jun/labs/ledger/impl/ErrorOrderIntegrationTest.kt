package com.jun.labs.ledger.impl

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 입력(400) → 존재(404) → 상태(409) → 금액(409), 거부되면 상태 불변 · 오류 본문 {code, message} */
class ErrorOrderIntegrationTest : ImplIntegrationTest() {

    private val t0 = Instant.parse("2350-01-01T00:00:00Z")
    private val future = t0.plus(Duration.ofDays(30))

    @BeforeEach
    fun setClock() {
        clock.now = t0
    }

    private fun assertError(r: HttpResult, status: Int, code: String) {
        assertEquals(status, r.status, r.body.toString())
        assertEquals(code, r.body["code"].asText(), r.body.toString())
        assertTrue(r.body["message"].asText().isNotBlank())
    }

    @Test
    fun `적립 입력 위반은 400이고 지갑 · 적립 · 이력이 생기지 않는다`() {
        val user = newUserId()
        val bodies = listOf(
            """{"amount":100,"expiresAt":"$future","sourceRef":"r"}""",
            """{"userId":0,"amount":100,"expiresAt":"$future","sourceRef":"r"}""",
            """{"userId":$user,"amount":0,"expiresAt":"$future","sourceRef":"r"}""",
            """{"userId":$user,"amount":100000001,"expiresAt":"$future","sourceRef":"r"}""",
            """{"userId":$user,"amount":1.5,"expiresAt":"$future","sourceRef":"r"}""",
            """{"userId":$user,"amount":"100","expiresAt":"$future","sourceRef":"r"}""",
            """{"userId":true,"amount":100,"expiresAt":"$future","sourceRef":"r"}""",
            """{"userId":$user,"amount":100,"expiresAt":"$t0","sourceRef":"r"}""",
            """{"userId":$user,"amount":100,"expiresAt":"2350-02-01T00:00:00","sourceRef":"r"}""",
            """{"userId":$user,"amount":100,"expiresAt":"tomorrow","sourceRef":"r"}""",
            """{"userId":$user,"amount":100,"sourceRef":"r"}""",
            """{"userId":$user,"amount":100,"expiresAt":"$future","sourceRef":"   "}""",
            """{"userId":$user,"amount":100,"expiresAt":"$future","sourceRef":"${"x".repeat(65)}"}""",
            """{"userId":$user,"amount":99999999999999999999,"expiresAt":"$future","sourceRef":"r"}""",
            """{"userId":$user,""",
        )
        for (body in bodies) assertError(post("/points/earn", body), 400, "INVALID_REQUEST")
        assertError(post("/points/earn", null), 400, "INVALID_REQUEST")
        assertEquals(0, count("point_wallet", user))
        assertEquals(0, count("point_earn", user))
        assertEquals(0, count("point_history", user))

        // 오프셋이 있는 시각은 받는다
        val ok = post("/points/earn", """{"userId":$user,"amount":100,"expiresAt":"2350-02-01T09:00:00+09:00","sourceRef":"${"x".repeat(64)}"}""")
        assertEquals(201, ok.status)
        assertEquals(Instant.parse("2350-02-01T00:00:00Z"), Instant.parse(ok.body["expiresAt"].asText()))
    }

    @Test
    fun `사용 — 입력 400 · 지갑 없음 409 · 잔액 부족 409, 거부되면 상태 불변`() {
        val user = newUserId()
        assertError(post("/points/use", """{"userId":$user,"amount":0,"orderRef":"o"}"""), 400, "INVALID_REQUEST")
        assertError(post("/points/use", """{"userId":$user,"amount":10,"orderRef":""}"""), 400, "INVALID_REQUEST")
        assertError(use(user, 10), 409, "INSUFFICIENT_BALANCE")
        assertEquals(0, count("point_wallet", user))

        val e = earn(user, 100, future)["earnId"].asLong()
        assertError(use(user, 101), 409, "INSUFFICIENT_BALANCE")
        assertEquals(100, balanceColumn(user))
        assertEquals(100L, earnRow(e)["remaining"])
        assertEquals(0, count("point_use", user))
        assertEquals(1, count("point_history", user))
    }

    @Test
    fun `사용 취소 — 입력이 존재보다 · 존재가 상태보다 · 상태가 금액보다 먼저`() {
        val user = newUserId()
        earn(user, 1000, future)
        val useId = use(user, 600).body["useId"].asLong()
        val missing = useId + 1_000_000

        // 입력(400)이 존재(404)보다 먼저
        assertError(post("/points/uses/$missing/cancel", """{"cancelAmount":0}"""), 400, "INVALID_REQUEST")
        assertError(post("/points/uses/$missing/cancel", """{"cancelAmount":1.5}"""), 400, "INVALID_REQUEST")
        for (bad in listOf("abc", "+5", "-1", "0", "99999999999999999999")) {
            assertError(post("/points/uses/$bad/cancel", "{}"), 400, "INVALID_REQUEST")
        }
        assertError(post("/points/uses/$missing/cancel", """{"cancelAmount":1}"""), 404, "USE_NOT_FOUND")

        // 금액 초과 409 · 상태 불변
        assertError(post("/points/uses/$useId/cancel", """{"cancelAmount":601}"""), 409, "CANCEL_AMOUNT_EXCEEDED")
        assertEquals(400, balanceColumn(user))
        assertEquals(0, count("point_earn", user) - 1)

        // {"cancelAmount": null} = 전부 → 이후 상태(409 USE_NOT_CANCELABLE)가 금액보다 먼저
        assertEquals(200, post("/points/uses/$useId/cancel", """{"cancelAmount":null}""").status)
        assertError(post("/points/uses/$useId/cancel", """{"cancelAmount":99999}"""), 409, "USE_NOT_CANCELABLE")
        assertError(post("/points/uses/$useId/cancel", "{}"), 409, "USE_NOT_CANCELABLE")
        assertEquals(1000, balanceColumn(user))
        assertEquals(2, count("point_earn", user))
    }

    @Test
    fun `적립 취소 · 조회 — 경로 400 · 없음 404 · 상태 409`() {
        val user = newUserId()
        val e = earn(user, 100, future)["earnId"].asLong()
        assertError(post("/points/earns/abc/cancel", null), 400, "INVALID_REQUEST")
        assertError(post("/points/earns/${e + 1_000_000}/cancel", null), 404, "EARN_NOT_FOUND")
        assertEquals(200, post("/points/earns/$e/cancel", null).status)
        assertError(post("/points/earns/$e/cancel", null), 409, "EARN_NOT_CANCELABLE")
        assertEquals(0, balanceColumn(user))

        assertError(get("/points/earns/${e + 1_000_000}"), 404, "EARN_NOT_FOUND")
        assertError(get("/points/uses/999999999999"), 404, "USE_NOT_FOUND")
        assertError(get("/points/uses/x1"), 400, "INVALID_REQUEST")
    }

    @Test
    fun `잔액 · 이력 조회 입력`() {
        val user = newUserId()
        assertEquals(0, get("/points/users/$user/balance").body["balance"].asLong())
        assertEquals(user, get("/points/users/$user/balance").body["userId"].asLong())
        assertError(get("/points/users/0/balance"), 400, "INVALID_REQUEST")
        assertError(get("/points/users/-3/history"), 400, "INVALID_REQUEST")
        for (bad in listOf("0", "101", "abc", "+5", "")) {
            assertError(get("/points/users/$user/history?limit=$bad"), 400, "INVALID_REQUEST")
        }
        assertEquals(200, get("/points/users/$user/history?limit=100").status)
        assertEquals(0, get("/points/users/$user/history").body.size())
    }

    @Test
    fun `Spring 표준 오류는 HTTP_상태 코드 · 헤더 유지`() {
        assertError(get("/points/nope"), 404, "HTTP_404")
        val r405 = get("/points/earn")
        assertError(r405, 405, "HTTP_405")
        assertTrue(r405.headers.allow.isNotEmpty())
        assertError(post("/points/earn", "hello", MediaType.TEXT_PLAIN), 415, "HTTP_415")
    }

    @Test
    fun `모르는 JSON 키는 400 — 사용 취소의 amount가 전부 취소로 바뀌지 않는다`() {
        val user = newUserId()
        earn(user, 1000, future)
        val useId = use(user, 600).body["useId"].asLong()

        assertError(post("/points/uses/$useId/cancel", """{"amount":100}"""), 400, "INVALID_REQUEST")
        assertError(post("/points/uses/$useId/cancel", """{"cancelAmount":100,"reason":"x"}"""), 400, "INVALID_REQUEST")
        assertEquals("USED", get("/points/uses/$useId").body["status"].asText())
        assertEquals(400, balanceColumn(user))
        assertEquals(1, count("point_earn", user))

        assertError(post("/points/earn", """{"userId":$user,"amount":1,"expiresAt":"$future","sourceRef":"r","memo":"x"}"""), 400, "INVALID_REQUEST")
        assertError(post("/points/use", """{"userId":$user,"amount":1,"orderRef":"o","orderId":"o"}"""), 400, "INVALID_REQUEST")
        assertEquals(1, count("point_earn", user))
        assertEquals(1, count("point_use", user))
    }

    @Test
    fun `사용 취소 본문 — 리터럴 null · {} · 본문 없음은 전부 취소`() {
        for (body in listOf("null", "{}", null)) {
            val user = newUserId()
            earn(user, 100, future)
            val useId = use(user, 70).body["useId"].asLong()
            val r = post("/points/uses/$useId/cancel", body)
            assertEquals(200, r.status, "$body → ${r.body}")
            assertEquals("CANCELED", r.body["status"].asText())
            assertEquals(70, r.body["canceledAmount"].asLong())
        }
    }
}

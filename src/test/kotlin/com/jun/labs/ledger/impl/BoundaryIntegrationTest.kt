package com.jun.labs.ledger.impl

import com.jun.labs.ledger.service.ExpirePointsUseCase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 지갑 없는 적립 건(DB 조작) · ref 형식 · expiresAt 경계.
 * 만료 배치 테스트만 t0(2600년) 구간을 쓰고, 나머지는 그 구간과 겹치지 않는 tFar(2700년)에서 적립한다 — 실행 순서와 무관하게.
 */
class BoundaryIntegrationTest : ImplIntegrationTest() {

    @Autowired lateinit var expirePoints: ExpirePointsUseCase

    private val t0 = Instant.parse("2600-01-01T00:00:00Z")
    private val tFar = Instant.parse("2700-01-01T00:00:00Z")

    private fun seedWalletlessEarn(user: Long, remaining: Long, expiresAt: Instant): Long =
        jdbc.queryForObject(
            """INSERT INTO point_earn(user_id, amount, remaining, source, source_ref, status, expires_at, created_at, updated_at)
               VALUES (?, ?, ?, 'EARN', 'seed', 'ACTIVE', ?, ?, ?) RETURNING id""",
            Long::class.java, user, remaining, remaining, Timestamp.from(expiresAt), Timestamp.from(t0), Timestamp.from(t0),
        )!!

    @Test
    fun `지갑 없는 적립 건 만료 — 지갑을 만들고 음수 잔액 · 이력 1행 · 같은 실행의 다른 건도 처리된다`() {
        clock.now = t0
        while (expirePoints.expire() > 0) Unit

        val orphanUser = newUserId()
        val orphan = seedWalletlessEarn(orphanUser, 70, t0.plus(Duration.ofDays(1)))
        val normalUser = newUserId()
        val normal = earn(normalUser, 30, t0.plus(Duration.ofDays(2)))["earnId"].asLong()

        clock.now = t0.plus(Duration.ofDays(3))
        // 드레인 직후라 이번 실행 대상은 이 테스트의 두 건뿐이다 — 아래에서 자기 행도 하나씩 확인한다
        assertEquals(2, expirePoints.expire())

        assertEquals("EXPIRED", earnRow(orphan)["status"])
        assertEquals(1, count("point_wallet", orphanUser))
        assertEquals(-70, balanceColumn(orphanUser))
        assertEquals(listOf(mapOf("type" to "EXPIRE", "amount" to 70L, "ref_id" to orphan, "balance_after" to -70L)), historyRows(orphanUser))
        assertEquals("EXPIRED", earnRow(normal)["status"])
        assertEquals(0, balanceColumn(normalUser))
    }

    @Test
    fun `지갑 없는 적립 건 취소 — 지갑을 만들고 음수 잔액 · 이력 1행`() {
        clock.now = tFar
        val user = newUserId()
        val orphan = seedWalletlessEarn(user, 40, tFar.plus(Duration.ofDays(10)))
        val r = post("/points/earns/$orphan/cancel", null)
        assertEquals(200, r.status, r.body.toString())
        assertEquals(-40, balanceColumn(user))
        assertEquals(listOf(mapOf("type" to "EARN_CANCEL", "amount" to 40L, "ref_id" to orphan, "balance_after" to -40L)), historyRows(user))
    }

    @Test
    fun `sourceRef · orderRef에 제어 문자(NUL 포함)가 있으면 400`() {
        clock.now = tFar
        val user = newUserId()
        val future = tFar.plus(Duration.ofDays(1))
        for (ref in listOf("a\\u0000b", "\\u0000", "tab\\there", "line\\nbreak", "\\u007f")) {
            val r = post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"$future","sourceRef":"$ref"}""")
            assertEquals(400, r.status, ref)
            assertEquals("INVALID_REQUEST", r.body["code"].asText())
        }
        assertEquals(0, count("point_earn", user))
        earn(user, 10, future)
        val u = post("/points/use", """{"userId":$user,"amount":1,"orderRef":"o\u0000"}""")
        assertEquals(400, u.status)
        assertEquals("INVALID_REQUEST", u.body["code"].asText())
        assertEquals(10, balanceColumn(user))
        assertEquals(0, count("point_use", user))
    }

    @Test
    fun `expiresAt 상한 9999-12-31T23_59_59Z는 통과 · 1초 넘으면 400`() {
        clock.now = tFar
        val user = newUserId()
        val ok = post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"9999-12-31T23:59:59Z","sourceRef":"max"}""")
        assertEquals(201, ok.status, ok.body.toString())
        assertEquals(Instant.parse("9999-12-31T23:59:59Z"), Instant.parse(ok.body["expiresAt"].asText()))
        assertEquals(Instant.parse("9999-12-31T23:59:59Z"), get("/points/earns/${ok.body["earnId"].asLong()}").body["expiresAt"].let { Instant.parse(it.asText()) })

        for (over in listOf("+10000-01-01T00:00:00Z", "9999-12-31T23:59:59-00:01")) {
            val r = post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"$over","sourceRef":"over"}""")
            assertEquals(400, r.status, over)
            assertEquals("INVALID_REQUEST", r.body["code"].asText())
        }
        assertEquals(1, count("point_earn", user))
    }

    @Test
    fun `sourceRef · orderRef 자리에 JSON 숫자 · 실수 · 불리언은 400`() {
        clock.now = tFar
        val user = newUserId()
        val future = tFar.plus(Duration.ofDays(1))
        for (ref in listOf("123", "1.5", "true", "false")) {
            val r = post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"$future","sourceRef":$ref}""")
            assertEquals(400, r.status, ref)
            assertEquals("INVALID_REQUEST", r.body["code"].asText())
        }
        for (expiresAt in listOf("4102444800", "true")) {
            assertEquals(400, post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":$expiresAt,"sourceRef":"r"}""").status, expiresAt)
        }
        assertEquals(0, count("point_earn", user))
        earn(user, 10, future)
        for (ref in listOf("7", "2.5", "true")) {
            val r = post("/points/use", """{"userId":$user,"amount":1,"orderRef":$ref}""")
            assertEquals(400, r.status, ref)
            assertEquals("INVALID_REQUEST", r.body["code"].asText())
        }
        assertEquals(0, count("point_use", user))
    }

    @Test
    fun `ref에 짝 없는 서로게이트가 있으면 400 · 짝이 맞는 이모지는 통과`() {
        clock.now = tFar
        val user = newUserId()
        val future = tFar.plus(Duration.ofDays(1))
        for (ref in listOf("a\\ud800", "\\udc00b", "\\ude00\\ud83d")) {
            val r = post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"$future","sourceRef":"$ref"}""")
            assertEquals(400, r.status, ref)
            assertEquals("INVALID_REQUEST", r.body["code"].asText())
        }
        assertEquals(201, post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"$future","sourceRef":"\ud83d\ude00"}""").status)
        val u = post("/points/use", """{"userId":$user,"amount":1,"orderRef":"o\udbff"}""")
        assertEquals(400, u.status)
        assertEquals(0, count("point_use", user))
    }

    @Test
    fun `ref 길이는 코드 포인트로 센다 — 한글 · 이모지 섞어 64는 통과 · 65는 400`() {
        clock.now = tFar
        val user = newUserId()
        val future = tFar.plus(Duration.ofDays(1))
        val ref64 = "가😀".repeat(32) // 64 코드 포인트 · UTF-16 96단위
        val ref65 = ref64 + "나"
        assertEquals(64, ref64.codePointCount(0, ref64.length))

        val ok = post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"$future","sourceRef":"$ref64"}""")
        assertEquals(201, ok.status, ok.body.toString())
        assertEquals(ref64, ok.body["sourceRef"].asText())
        assertEquals(ref64, earnRow(ok.body["earnId"].asLong())["source_ref"])
        assertEquals(400, post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"$future","sourceRef":"$ref65"}""").status)

        assertEquals(201, post("/points/use", """{"userId":$user,"amount":1,"orderRef":"$ref64"}""").status)
        assertEquals(400, post("/points/use", """{"userId":$user,"amount":1,"orderRef":"$ref65"}""").status)
    }

    @Test
    fun `expiresAt은 마이크로초 단위로 비교한다 — now + 500ns는 400 · now + 1µs는 통과`() {
        clock.now = tFar
        val user = newUserId()
        val r = post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"${tFar.plusNanos(500)}","sourceRef":"ns"}""")
        assertEquals(400, r.status, r.body.toString())
        assertEquals("INVALID_REQUEST", r.body["code"].asText())
        assertEquals(0, count("point_earn", user))
        val ok = post("/points/earn", """{"userId":$user,"amount":10,"expiresAt":"${tFar.plusNanos(1000)}","sourceRef":"us"}""")
        assertEquals(201, ok.status, ok.body.toString())
    }

    @Test
    fun `환원 적립 만료가 상한을 넘으면 상한으로 자르고 사용 취소는 성공한다`() {
        clock.now = Instant.parse("9999-06-01T00:00:00Z")
        val user = newUserId()
        earn(user, 100, Instant.parse("9999-12-31T23:59:59Z"))
        val useId = use(user, 40).body["useId"].asLong()
        val r = post("/points/uses/$useId/cancel", null)
        assertEquals(200, r.status, r.body.toString())
        val restored = jdbc.queryForObject(
            "SELECT expires_at FROM point_earn WHERE user_id = ? AND source = 'USE_CANCEL' AND source_ref = ?",
            Timestamp::class.java, user, useId.toString(),
        )!!.toInstant()
        assertEquals(Instant.parse("9999-12-31T23:59:59Z"), restored)
        assertEquals(100, balanceColumn(user))
    }
}

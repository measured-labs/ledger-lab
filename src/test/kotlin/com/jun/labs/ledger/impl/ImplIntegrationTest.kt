package com.jun.labs.ledger.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.ledger.support.MutableClock
import com.jun.labs.ledger.support.Postgres
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.atomic.AtomicLong

/** impl 통합 테스트 공통 — 한 컨텍스트(배치 3건 · 스케줄러 끔 · 옮길 수 있는 Clock)를 클래스끼리 공유한다 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ledger.expiry.enabled=false", "ledger.expiry.batch-size=3"],
)
@Import(ImplIntegrationTest.ClockConfig::class)
abstract class ImplIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var clock: MutableClock

    private val mapper = ObjectMapper()

    @TestConfiguration
    class ClockConfig {
        @Bean
        @Primary
        fun clock(): MutableClock = MutableClock()
    }

    class HttpResult(val status: Int, val body: JsonNode, val headers: HttpHeaders)

    fun post(path: String, json: String?, contentType: MediaType? = MediaType.APPLICATION_JSON): HttpResult =
        exchange(HttpMethod.POST, path, json, contentType)

    fun get(path: String): HttpResult = exchange(HttpMethod.GET, path, null, null)

    private fun exchange(method: HttpMethod, path: String, body: String?, contentType: MediaType?): HttpResult {
        val headers = HttpHeaders().apply { if (body != null && contentType != null) this.contentType = contentType }
        val response = rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
        val json = response.body?.let { mapper.readTree(it) } ?: mapper.nullNode()
        return HttpResult(response.statusCode.value(), json, response.headers)
    }

    fun earn(userId: Long, amount: Long, expiresAt: java.time.Instant, sourceRef: String = "ref-$amount"): JsonNode {
        val r = post("/points/earn", """{"userId":$userId,"amount":$amount,"expiresAt":"$expiresAt","sourceRef":"$sourceRef"}""")
        check(r.status == 201) { "적립 실패 ${r.status} ${r.body}" }
        return r.body
    }

    fun use(userId: Long, amount: Long, orderRef: String = "order-$amount"): HttpResult =
        post("/points/use", """{"userId":$userId,"amount":$amount,"orderRef":"$orderRef"}""")

    fun balanceColumn(userId: Long): Long =
        jdbc.queryForObject("SELECT balance FROM point_wallet WHERE user_id = ? ORDER BY id LIMIT 1", Long::class.java, userId)!!

    fun sumActiveRemaining(userId: Long): Long =
        jdbc.queryForObject("SELECT COALESCE(SUM(remaining), 0) FROM point_earn WHERE user_id = ? AND status = 'ACTIVE'", Long::class.java, userId)!!

    fun earnRow(earnId: Long): Map<String, Any> = jdbc.queryForMap("SELECT * FROM point_earn WHERE id = ?", earnId)

    fun historyRows(userId: Long): List<Map<String, Any>> =
        jdbc.queryForList("SELECT type, amount, ref_id, balance_after FROM point_history WHERE user_id = ? ORDER BY id", userId)

    fun count(table: String, userId: Long): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE user_id = ?", Long::class.java, userId)!!

    companion object {
        private val userIds = AtomicLong(System.currentTimeMillis() * 1000)

        fun newUserId(): Long = userIds.incrementAndGet()

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = Postgres.register(registry)
    }
}


package com.jun.labs.ledger.blind

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.ledger.service.ExpirePointsUseCase
import com.jun.labs.ledger.support.MutableClock
import com.jun.labs.ledger.support.Postgres
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

val BLIND_MAPPER = ObjectMapper()

/** JSON 본문에서 키 자체를 빼고 싶을 때 쓰는 표시. */
object Omit

fun json(vararg pairs: Pair<String, Any?>): String =
    BLIND_MAPPER.writeValueAsString(linkedMapOf(*pairs.filter { it.second !== Omit }.toTypedArray()))

fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull || it.isMissingNode }?.asText()

fun JsonNode.instant(field: String): Instant = OffsetDateTime.parse(get(field).asText()).toInstant()

data class HttpResult(val status: Int, val body: String, val headers: Map<String, List<String>> = emptyMap()) {
    val json: JsonNode by lazy { BLIND_MAPPER.readTree(body.ifBlank { "{}" }) }
    val code: String? get() = json.textOrNull("code")

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.joinToString(",")
}

class JsonHttp(private val baseUrl: String) {
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun get(path: String) = send("GET", path, null)

    fun post(path: String, body: String?, contentType: String = "application/json") = send("POST", path, body, contentType)

    fun send(method: String, path: String, body: String?, contentType: String = "application/json"): HttpResult {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", contentType)
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        builder.method(method, publisher)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body() ?: "", response.headers().map())
    }
}

/** 사용자 한 명의 DB 상태 — 거부된 요청 뒤 "상태 불변" 비교용. */
data class UserSnapshot(
    val wallets: List<Map<String, Any?>>,
    val earns: List<Map<String, Any?>>,
    val uses: List<Map<String, Any?>>,
    val histories: List<Map<String, Any?>>,
)

@TestConfiguration(proxyBeanMethods = false)
class BlindClockConfig {
    /** 빈 이름 `clock` — 앱 쪽 Clock 빈(`systemClock`)과 이름이 겹치지 않고 @Primary로 이긴다. */
    @Bean
    @Primary
    fun clock(): Clock = BLIND_CLOCK
}

/** 블라인드 스위트 전체가 공유하는 시계 — 앞으로만 움직인다(다른 테스트의 데이터가 과거로 되돌아가지 않게). */
val BLIND_CLOCK = MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS))

/** 만료 배치 상한을 작게 둔다 — 상한 동작을 몇 건으로 확인하기 위해. */
const val BLIND_BATCH_SIZE = 3

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BlindClockConfig::class)
abstract class BlindTestSupport {

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @Autowired
    protected lateinit var expirePoints: ExpirePointsUseCase

    protected val clock: MutableClock get() = BLIND_CLOCK

    protected val app: JsonHttp get() = JsonHttp("http://localhost:$port")

    protected fun now(): Instant = clock.instant()

    protected fun iso(instant: Instant): String = instant.toString()

    protected fun inFuture(d: Duration): String = iso(now().plus(d))

    /** 테스트마다 새 사용자 id — 다른 클래스 · 구현 테스트와 섞이지 않게 큰 난수 구간에서 증가. */
    protected fun newUserId(): Long = USER_SEQ.incrementAndGet()

    // ---- HTTP ----

    protected fun earn(
        userId: Any?,
        amount: Any? = 1_000L,
        expiresAt: Any? = inFuture(Duration.ofDays(30)),
        sourceRef: Any? = "earn-ref",
    ): HttpResult = app.post(
        "/points/earn",
        json("userId" to userId, "amount" to amount, "expiresAt" to expiresAt, "sourceRef" to sourceRef),
    )

    protected fun earnOk(
        userId: Long,
        amount: Long,
        expiresIn: Duration = Duration.ofDays(30),
        sourceRef: String = "earn-ref",
    ): JsonNode {
        val res = earn(userId, amount, inFuture(expiresIn), sourceRef)
        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        return res.json
    }

    protected fun use(userId: Any?, amount: Any? = 100L, orderRef: Any? = "order-ref"): HttpResult = app.post(
        "/points/use",
        json("userId" to userId, "amount" to amount, "orderRef" to orderRef),
    )

    protected fun useOk(userId: Long, amount: Long, orderRef: String = "order-ref"): JsonNode {
        val res = use(userId, amount, orderRef)
        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        return res.json
    }

    /** body = null 이면 본문 없이 보낸다. */
    protected fun cancelUse(useId: Any, body: String? = "{}"): HttpResult = app.post("/points/uses/$useId/cancel", body)

    protected fun cancelEarn(earnId: Any, body: String? = null): HttpResult = app.post("/points/earns/$earnId/cancel", body)

    protected fun balance(userId: Any): HttpResult = app.get("/points/users/$userId/balance")

    protected fun balanceOf(userId: Long): Long {
        val res = balance(userId)
        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        return res.json["balance"].asLong()
    }

    protected fun history(userId: Any, limit: String? = null): HttpResult =
        app.get("/points/users/$userId/history" + (limit?.let { "?limit=$it" } ?: ""))

    protected fun getEarn(id: Any): HttpResult = app.get("/points/earns/$id")

    protected fun getUse(id: Any): HttpResult = app.get("/points/uses/$id")

    // ---- 만료 배치 ----

    protected fun expire(): Int = expirePoints.expire()

    /** 지금 시각 기준 만료 대상을 모두 처리해 둔다 — 이후 expire() 반환 값을 이 테스트의 데이터로만 판단하기 위해. */
    protected fun drainExpiry() {
        var guard = 0
        while (expire() > 0) {
            guard++
            check(guard < 10_000) { "만료 배치가 끝나지 않는다" }
        }
    }

    /**
     * 배치 한 번 — 실행 전 DB에서 고른 기대 대상(ACTIVE · expires_at <= now · expires_at, id 순 · 최대 batch-size)과
     * 반환 값 · 처리된 행이 같은지 확인하고 그 id 목록을 돌려준다. 배치는 전역이라 다른 테스트가 남긴 행도 대상일 수 있다.
     */
    protected fun expireOnceChecked(): List<Long> {
        val expected = expirableIds(BLIND_BATCH_SIZE)
        val n = expire()
        assertThat(n).describedAs("반환 값 = 이번에 EXPIRED로 바꾼 건 수").isEqualTo(expected.size)
        expected.forEach { id ->
            val row = earnRow(id)
            assertThat(row.str("status")).describedAs("earn $id").isEqualTo("EXPIRED")
            assertThat(row.long("remaining")).describedAs("earn $id").isEqualTo(0L)
        }
        return expected
    }

    /** 내 적립 건들이 모두 처리될 때까지 배치를 돌린다(매 실행을 expireOnceChecked로 확인). */
    protected fun expireUntilDone(mine: Collection<Long>): List<List<Long>> {
        val runs = mutableListOf<List<Long>>()
        var guard = 0
        while (mine.any { earnRow(it).str("status") == "ACTIVE" }) {
            runs += expireOnceChecked()
            check(++guard < 10_000) { "만료 배치가 끝나지 않는다" }
        }
        return runs
    }

    /** 지금 시각 기준 만료 대상(ACTIVE · expires_at <= now)을 배치 순서대로 최대 n개. */
    protected fun expirableIds(limit: Int): List<Long> = jdbc.queryForList(
        "select id from point_earn where status = 'ACTIVE' and expires_at <= ? order by expires_at, id limit ?",
        Long::class.java,
        Timestamp.from(now()),
        limit,
    )

    // ---- DB ----

    protected fun walletRows(userId: Long): List<Map<String, Any?>> =
        jdbc.queryForList("select * from point_wallet where user_id = ? order by id", userId)

    protected fun earnRows(userId: Long): List<Map<String, Any?>> =
        jdbc.queryForList("select * from point_earn where user_id = ? order by id", userId)

    protected fun earnRow(id: Long): Map<String, Any?> = jdbc.queryForMap("select * from point_earn where id = ?", id)

    protected fun useRows(userId: Long): List<Map<String, Any?>> =
        jdbc.queryForList("select * from point_use where user_id = ? order by id", userId)

    protected fun useRow(id: Long): Map<String, Any?> = jdbc.queryForMap("select * from point_use where id = ?", id)

    protected fun historyRows(userId: Long): List<Map<String, Any?>> =
        jdbc.queryForList("select * from point_history where user_id = ? order by id", userId)

    protected fun snapshot(userId: Long) = UserSnapshot(walletRows(userId), earnRows(userId), useRows(userId), historyRows(userId))

    protected fun count(table: String): Long = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!

    protected fun activeRemainingSum(userId: Long): Long = jdbc.queryForObject(
        "select coalesce(sum(remaining), 0) from point_earn where user_id = ? and status = 'ACTIVE'",
        Long::class.java,
        userId,
    )!!

    protected fun insertWallet(userId: Long, balance: Long): Long = jdbc.queryForObject(
        "insert into point_wallet(user_id, balance, updated_at) values (?, ?, ?) returning id",
        Long::class.java,
        userId,
        balance,
        Timestamp.from(now()),
    )!!

    protected fun Map<String, Any?>.long(col: String): Long = (this[col] as Number).toLong()

    protected fun Map<String, Any?>.str(col: String): String? = this[col]?.toString()

    protected fun Map<String, Any?>.time(col: String): Instant = when (val v = this[col]) {
        is Timestamp -> v.toInstant()
        is OffsetDateTime -> v.toInstant()
        is Instant -> v
        else -> error("$col 시각 형식 알 수 없음: $v")
    }

    // ---- 단언 ----

    /** 오류 본문은 언제나 {code, message}. */
    protected fun assertError(res: HttpResult, status: Int, code: String) {
        assertThat(res.status).describedAs(res.body).isEqualTo(status)
        assertThat(res.code).describedAs(res.body).isEqualTo(code)
        assertThat(res.json.textOrNull("message")).describedAs("message 필드 — ${res.body}").isNotNull()
        assertThat(res.json.fieldNames().asSequence().toSet()).describedAs("오류 본문 필드는 정확히 {code, message} — ${res.body}")
            .isEqualTo(setOf("code", "message"))
    }

    /**
     * 정상 경로 불변식 — 잔액(API · 지갑 행) = Σ ACTIVE 적립 건 remaining(배치 전 창의 만료 건 포함),
     * 마지막 이력의 balance_after = 지금 잔액, remaining은 0..amount, ACTIVE가 아니면 remaining 0.
     */
    protected fun assertInvariant(userId: Long) {
        val balance = balanceOf(userId)
        assertThat(balance).describedAs("잔액 = Σ ACTIVE remaining").isEqualTo(activeRemainingSum(userId))
        val wallets = walletRows(userId)
        if (wallets.isNotEmpty()) assertThat(wallets.first().long("balance")).isEqualTo(balance)
        assertThat(balance).isGreaterThanOrEqualTo(0L)
        earnRows(userId).forEach { e ->
            assertThat(e.long("remaining")).isBetween(0L, e.long("amount"))
            if (e.str("status") != "ACTIVE") assertThat(e.long("remaining")).isEqualTo(0L)
        }
        useRows(userId).forEach { u ->
            assertThat(u.long("canceled_amount")).isBetween(0L, u.long("amount"))
        }
        val histories = historyRows(userId)
        if (histories.isNotEmpty()) assertThat(histories.last().long("balance_after")).isEqualTo(balance)
    }

    companion object {
        private val USER_SEQ = AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000_000L) * 1_000L + 7_000_000_000_000L)

        @JvmStatic
        @DynamicPropertySource
        fun blindProperties(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("ledger.expiry.enabled") { "false" }
            registry.add("ledger.expiry.batch-size") { BLIND_BATCH_SIZE.toString() }
        }
    }
}

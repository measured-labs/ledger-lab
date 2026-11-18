package com.jun.labs.ledger.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.stream.Stream

/** 입력 검증 — ADR-000 §6.1 표 전부 · test-contract §3. 위반 = 400 INVALID_REQUEST {code, message} · 상태 불변. */
class ValidationBlindTest : BlindTestSupport() {

    /** 값 자리에 FRESH_USER 를 두면 테스트의 새 사용자 id 를 쓴다. */
    data class Case(val label: String, val fields: List<Pair<String, Any?>>) {
        override fun toString() = label
    }

    private fun resolve(fields: List<Pair<String, Any?>>, userId: Long): String =
        json(*fields.map { (k, v) ->
            k to when {
                v === FRESH_USER -> userId
                v === NOW -> iso(now())
                v is Duration -> iso(now().plus(v))
                else -> v
            }
        }.toTypedArray())

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEarnCases")
    fun `적립 입력 검증 위반 - 400, 행 생기지 않음`(case: Case) {
        val userId = newUserId()
        val earnsBefore = count("point_earn")
        val walletsBefore = count("point_wallet")
        val historyBefore = count("point_history")

        val res = app.post("/points/earn", resolve(case.fields, userId))

        assertError(res, 400, "INVALID_REQUEST")
        assertThat(count("point_earn")).isEqualTo(earnsBefore)
        assertThat(count("point_wallet")).isEqualTo(walletsBefore)
        assertThat(count("point_history")).isEqualTo(historyBefore)
        assertThat(snapshot(userId)).isEqualTo(UserSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUseCases")
    fun `사용 입력 검증 위반 - 400, 상태 불변`(case: Case) {
        val userId = newUserId()
        earnOk(userId, 1_000_000L)
        val before = snapshot(userId)
        val usesBefore = count("point_use")

        val res = app.post("/points/use", resolve(case.fields, userId))

        assertError(res, 400, "INVALID_REQUEST")
        assertThat(snapshot(userId)).isEqualTo(before)
        assertThat(count("point_use")).isEqualTo(usesBefore)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCancelBodies")
    fun `사용 취소 본문 검증 위반 - 400, 상태 불변`(label: String, body: String) {
        val userId = newUserId()
        earnOk(userId, 1_000L)
        val useId = useOk(userId, 500L)["useId"].asLong()
        val before = snapshot(userId)

        val res = cancelUse(useId, body)

        assertThat(res.status).describedAs("$label → ${res.body}").isEqualTo(400)
        assertError(res, 400, "INVALID_REQUEST")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("badPathIds")
    fun `경로 id 가 양의 정수 · Long 범위가 아니면 400`(endpoint: String, rawId: String) {
        val res = when (endpoint) {
            "POST uses cancel" -> cancelUse(rawId, "{}")
            "POST earns cancel" -> cancelEarn(rawId)
            "GET earn" -> getEarn(rawId)
            "GET use" -> getUse(rawId)
            "GET balance" -> balance(rawId)
            "GET history" -> history(rawId)
            else -> error(endpoint)
        }
        assertError(res, 400, "INVALID_REQUEST")
    }

    @ParameterizedTest(name = "limit={0}")
    @ValueSource(strings = ["0", "101", "-1", "+5", "abc", "1.5", "9223372036854775808"])
    fun `이력 limit 이 정수 1~100 이 아니면 400`(limit: String) {
        val userId = newUserId()
        earnOk(userId, 10L)
        assertError(history(userId, limit), 400, "INVALID_REQUEST")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["{", "abc", """{"userId": }""", "[1, 2"])
    fun `읽을 수 없는 JSON 본문 - 적립 · 사용 · 사용 취소 모두 400`(body: String) {
        val userId = newUserId()
        earnOk(userId, 1_000L)
        val useId = useOk(userId, 100L)["useId"].asLong()
        val before = snapshot(userId)

        assertError(app.post("/points/earn", body), 400, "INVALID_REQUEST")
        assertError(app.post("/points/use", body), 400, "INVALID_REQUEST")
        assertError(cancelUse(useId, body), 400, "INVALID_REQUEST")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    companion object {
        private val FRESH_USER = Any()
        private val NOW = Any()
        private val FAR = Duration.ofDays(30)

        private fun earnCase(label: String, vararg overrides: Pair<String, Any?>): Case {
            val base = linkedMapOf<String, Any?>("userId" to FRESH_USER, "amount" to 1_000L, "expiresAt" to FAR, "sourceRef" to "ref-1")
            overrides.forEach { (k, v) -> base[k] = v }
            return Case(label, base.entries.filter { it.value !== Omit }.map { it.key to it.value })
        }

        private fun useCase(label: String, vararg overrides: Pair<String, Any?>): Case {
            val base = linkedMapOf<String, Any?>("userId" to FRESH_USER, "amount" to 100L, "orderRef" to "order-1")
            overrides.forEach { (k, v) -> base[k] = v }
            return Case(label, base.entries.filter { it.value !== Omit }.map { it.key to it.value })
        }

        @JvmStatic
        fun invalidEarnCases(): Stream<Case> = Stream.of(
            earnCase("userId 생략", "userId" to Omit),
            earnCase("userId null", "userId" to null),
            earnCase("userId 0", "userId" to 0L),
            earnCase("userId 음수", "userId" to -1L),
            earnCase("userId 소수", "userId" to 1.5),
            earnCase("userId 문자열", "userId" to "100"),
            earnCase("userId true", "userId" to true),
            earnCase("amount 생략", "amount" to Omit),
            earnCase("amount null", "amount" to null),
            earnCase("amount 0", "amount" to 0L),
            earnCase("amount 음수", "amount" to -1L),
            earnCase("amount 소수", "amount" to 1.5),
            earnCase("amount 정수 모양 소수 1000.0", "amount" to 1000.0),
            earnCase("amount 문자열", "amount" to "1000"),
            earnCase("amount true", "amount" to true),
            earnCase("amount 100,000,001", "amount" to 100_000_001L),
            earnCase("amount Long 최대", "amount" to Long.MAX_VALUE),
            earnCase("expiresAt 생략", "expiresAt" to Omit),
            earnCase("expiresAt null", "expiresAt" to null),
            earnCase("expiresAt 과거", "expiresAt" to Duration.ofSeconds(-1)),
            earnCase("expiresAt 지금", "expiresAt" to NOW),
            earnCase("expiresAt 시각 아님", "expiresAt" to "tomorrow"),
            earnCase("expiresAt 숫자", "expiresAt" to 1_900_000_000L),
            earnCase("sourceRef 생략", "sourceRef" to Omit),
            earnCase("sourceRef null", "sourceRef" to null),
            earnCase("sourceRef 빈 문자열", "sourceRef" to ""),
            earnCase("sourceRef 공백", "sourceRef" to "   "),
            earnCase("sourceRef 65자", "sourceRef" to "s".repeat(65)),
        )

        @JvmStatic
        fun invalidUseCases(): Stream<Case> = Stream.of(
            useCase("userId 생략", "userId" to Omit),
            useCase("userId null", "userId" to null),
            useCase("userId 0", "userId" to 0L),
            useCase("userId 음수", "userId" to -1L),
            useCase("userId 소수", "userId" to 1.5),
            useCase("userId 문자열", "userId" to "100"),
            useCase("amount 생략", "amount" to Omit),
            useCase("amount null", "amount" to null),
            useCase("amount 0", "amount" to 0L),
            useCase("amount 음수", "amount" to -5L),
            useCase("amount 소수", "amount" to 1.5),
            useCase("amount 문자열", "amount" to "100"),
            useCase("amount true", "amount" to true),
            useCase("amount 100,000,001", "amount" to 100_000_001L),
            useCase("orderRef 생략", "orderRef" to Omit),
            useCase("orderRef null", "orderRef" to null),
            useCase("orderRef 빈 문자열", "orderRef" to ""),
            useCase("orderRef 공백", "orderRef" to " \t "),
            useCase("orderRef 65자", "orderRef" to "o".repeat(65)),
        )

        @JvmStatic
        fun invalidCancelBodies(): Stream<Arguments> = Stream.of(
            Arguments.of("cancelAmount 0", json("cancelAmount" to 0L)),
            Arguments.of("cancelAmount 음수", json("cancelAmount" to -1L)),
            Arguments.of("cancelAmount 소수", json("cancelAmount" to 1.5)),
            Arguments.of("cancelAmount 문자열", json("cancelAmount" to "100")),
            Arguments.of("cancelAmount true", json("cancelAmount" to true)),
            Arguments.of("cancelAmount 100,000,001", json("cancelAmount" to 100_000_001L)),
        )

        private val ENDPOINTS = listOf("POST uses cancel", "POST earns cancel", "GET earn", "GET use", "GET balance", "GET history")
        private val BAD_IDS = listOf("+5", "-1", "0", "abc", "1.5", "9223372036854775808")

        @JvmStatic
        fun badPathIds(): Stream<Arguments> =
            ENDPOINTS.flatMap { e -> BAD_IDS.map { Arguments.of(e, it) } }.stream()
    }
}

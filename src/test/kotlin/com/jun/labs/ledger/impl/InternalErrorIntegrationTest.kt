package com.jun.labs.ledger.impl

import com.jun.labs.ledger.service.GetPointsUseCase
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 계약 밖 예외 → 500 INTERNAL_ERROR · 고정 문구 · 원인은 본문에 없다. 빈을 바꾸므로 다른 클래스와 컨텍스트를 나눈다 */
class InternalErrorIntegrationTest : ImplIntegrationTest() {

    @MockitoBean lateinit var getPointsUseCase: GetPointsUseCase

    @Test
    fun `서비스에서 RuntimeException이 나면 500 INTERNAL_ERROR 고정 문구이고 예외 메시지는 본문에 없다`() {
        val secret = "db-password=hunter2 at 10.0.0.5"
        `when`(getPointsUseCase.balance(anyLong())).thenThrow(RuntimeException(secret))

        val raw = rest.exchange("/points/users/1/balance", HttpMethod.GET, HttpEntity.EMPTY, String::class.java)
        assertEquals(500, raw.statusCode.value())
        assertFalse(raw.body!!.contains("hunter2"), raw.body)
        assertFalse(raw.body!!.contains("RuntimeException"), raw.body)

        val r = get("/points/users/1/balance")
        assertEquals("INTERNAL_ERROR", r.body["code"].asText())
        assertEquals("서버 오류가 발생했습니다.", r.body["message"].asText())
        assertEquals(setOf("code", "message"), r.body.fieldNames().asSequence().toSet())
    }
}

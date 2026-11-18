package com.jun.labs.ledger.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 모르는 키는 읽기 실패. 정수 자리는 JSON 정수만, 문자열 자리는 JSON 문자열만 받는다 — 1.5 · "100" · true(정수 자리), 123 · 1.5 · true(문자열 자리)는 읽기 실패(400 · ADR-000 §6.1) */
@Configuration
class JacksonConfig {
    @Bean
    fun strictIntegerCustomizer() = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        // 모르는 키는 400 — 오타 키(예: 사용 취소의 "amount")가 조용히 빠져 null(전부 취소)로 바뀌지 않게
        builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        builder.postConfigurer { mapper ->
            mapper.coercionConfigFor(LogicalType.Integer).apply {
                setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }
            mapper.coercionConfigFor(LogicalType.Textual).apply {
                setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }
        }
    }
}

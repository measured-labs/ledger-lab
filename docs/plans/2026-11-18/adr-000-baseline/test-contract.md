# 테스트 계약 — ledger-lab ADR-000 (구현 워커 · blind 워커 공통)

> ADR-000(`docs/adr/ADR-000-baseline-domain.md`)이 정하지 않은 시험 접점만 여기서 정한다. 나머지는 ADR-000 §2~§6과 명세 `requirement-spec.md`가 정본이다.

## 1. 시각

- 앱은 현재 시각을 **주입된 `java.time.Clock` 빈에서만** 얻는다(`Instant.now()` 직접 호출 금지). 앱 기본 빈은 `Clock.systemUTC()`.
- 테스트는 `@TestConfiguration`의 `@Bean @Primary fun clock(): Clock = MutableClock(...)`(`src/test/kotlin/com/jun/labs/ledger/support/MutableClock.kt`)로 바꿔 끼운다. 앱의 Clock 빈은 이 덮어쓰기를 막지 않아야 한다(이름이 같은 빈을 만들지 말 것 — 빈 이름 `clock`을 쓰지 않는다. 앱 쪽 빈 이름은 `systemClock`).

## 2. 만료 배치 호출

- 서비스 인터페이스 `com.jun.labs.ledger.service.ExpirePointsUseCase` — `fun expire(): Int`(이번 실행에서 EXPIRED로 바꾼 적립 건 수). 실행 한 번 = 트랜잭션 하나 · 최대 `ledger.expiry.batch-size`건 · `expiresAt` 오름차순(같으면 id 오름차순).
- 스케줄러는 `ledger.expiry.enabled=false`면 등록되지 않는다. 테스트는 이 값을 false로 두고 빈을 주입해 직접 부른다.
- 설정 키: `ledger.expiry.enabled`(기본 true) · `ledger.expiry.interval`(기본 60s) · `ledger.expiry.batch-size`(기본 1000) · `ledger.restore.valid-days`(기본 365).

## 3. HTTP

- JSON 필드 이름 · 상태 코드 · 오류 코드는 ADR-000 §6 그대로. 시각은 ISO-8601(UTC `Z` 또는 오프셋) 문자열.
- 오류 본문은 언제나 `{code, message}`. ADR §6 표에 없는 오류: Spring 표준 예외(404 없는 경로 · 405 · 415 등)는 `code = "HTTP_<상태>"`(응답 헤더 유지 — 405의 `Allow` 등). 406(클라이언트가 JSON을 받지 않음)은 JSON 본문을 쓸 수 없으므로 본문을 보장하지 않는다(리뷰 루프 1 수정), 그 밖의 예외는 `500 INTERNAL_ERROR`. 5xx `message`는 고정 문구.
- 정수 필드(`userId` · `amount` · `cancelAmount`)는 JSON 정수만 받는다: `1.5` · `"100"` · `true` = 400. 경로 id와 `limit`은 숫자만(`+5` · `-1` · `abc` = 400), Long 범위 밖 = 400.
- `GET /points/earns/{id}` 없으면 `404 EARN_NOT_FOUND` · `GET /points/uses/{id}` 없으면 `404 USE_NOT_FOUND`.
- 사용 취소 본문: 없거나 `{}` · `{"cancelAmount": null}` = 남은 사용액 전부.
- 적립 취소는 본문을 읽지 않는다.

## 4. DB 직접 확인

- 테스트는 `JdbcTemplate`으로 표 `point_wallet` · `point_earn` · `point_use` · `point_history`(ADR §5 열 이름)를 읽어 확인해도 된다. 쓰기(시드 · 조작)도 허용(예: 같은 사용자 지갑 2개 만들기).
- 사용자 id는 테스트마다 새 값을 써서 클래스끼리 섞이지 않게 한다(DB 컨테이너는 JVM당 하나 — `support/Postgres.kt`).

## 5. 파일 경계

- 구현 워커: `src/main/**`, `src/test/kotlin/com/jun/labs/ledger/impl/**`, `src/test/kotlin/com/jun/labs/ledger/LedgerApplicationTests.kt`. `blind/` 아래는 열지 않는다.
- blind 워커: `src/test/kotlin/com/jun/labs/ledger/blind/**`만 쓴다. `src/main/**`와 `impl/` 아래는 열지 않는다.
- 공통(읽기만): `build.gradle.kts`, `src/main/resources/application.yml`, `src/test/kotlin/com/jun/labs/ledger/support/**`, 이 문서.

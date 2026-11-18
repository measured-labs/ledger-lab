# 작업 기록 — ledger-lab ADR-000 베이스라인

> 명세: `requirement-spec.md` · 브랜치 `feat/adr-000-baseline`(기준 431182c) · stakes 높음 · MODE auto

## 타임라인 (시각 | 사건 | 결과)

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-11-18 | 인터뷰 8문항 · ADR-000 설계 초안 · 설계 선검증(codex medium 3건 반영) — interview.md | 명세 대기(payment 마감 뒤) |
| 2026-11-18 19:1x(시각 정정) | payment PR #1 병합(fc037ed) 뒤 명세 작성 · 사용자 "응 합의, 제안 3가지 넣고 auto로 진행해줘" → set-state spec-approved · mode auto · Q/A 9~11 채택 | SPEC=1 · MODE=auto |
| 2026-11-18 19:2x(시각 정정) | 설계 선검증 생략 근거: 이미 수행(interview.md '설계 선검증' — codex medium · 3건 반영) · 이번 추가(Q/A 9~11)는 캡슐화 · 검사 위치 · 문서 방식이라 새 동작 불변식 없음 | 생략(근거 기록) |
| 2026-11-18 19:2x(시각 정정) | 메인: 공통 골격(build.gradle.kts JPA · Flyway · PostgreSQL · Testcontainers 1.21.4 · plugin.jpa · allOpen / application.yml DB 기본값 없음 / test support `support/Postgres.kt` / LedgerApplicationTests Testcontainers) · compileTestKotlin rc=0 → 구현 워커 ∥ blind 워커 병렬 착수(서로의 파일 열람 금지: 구현 = `src/main/**` + `src/test/kotlin/com/jun/labs/ledger/impl/**` · blind = `src/test/kotlin/com/jun/labs/ledger/blind/**`) | 착수 |
| 2026-11-18 19:37 | task 02 회수(blind 워커 · Opus): `blind/` 9클래스 162개 · 금지 파일 미열람 보고 · 1차 1실패 = 테스트 격리(전역 만료 배치에 다른 테스트 건 섞임) → 실행 전 DB에서 기대 대상 선정으로 수정 · 2차 162/162(19:37 구현 상태) · 모호점 10(USE_CANCEL 이력 ref_id 사용 건/새 적립 둘 다 허용 등) | 구현 회수 대기 |
| 2026-11-18 19:40 | task 01·03 회수(구현 워커 · Opus · 142,533 토큰): main 25파일 · impl 테스트 22 · 가정 1 실증(파생 메서드 + `Limit`만 · @Query 0) · 결정 넘김 5: ① USE_CANCEL 이력 ref_id = 사용 건 id(채택 — ADR 명시) ② 적립 취소 · 만료 `deduct`가 잔액 초과면 예외 → 한 사용자 때문에 배치 전체 롤백 · 다음 실행도 막힘 ③ 지갑 없는 적립(DB 조작만) ④ expiresAt 오프셋 필수 ⑤ Spring 5xx 고정 문구 · ADR 내부 불일치 1(§1 'PK와 FK' vs §5 FK 없음) | 메인 판정 |
| 2026-11-18 19:4x | 메인 판정 ②: ADR §8이 '음수 잔액'을 정합성 검증기가 세는 항목으로 둠 → 평범한 구현은 그냥 뺀다 · `PointWallet.deduct` 잔액 검사 제거(음수 허용 · 이유 주석) · 도메인 테스트 갱신(음수로 남음 단언) · ①은 사용 경로만 거른다고 ADR 명시 예정 · ③ ④ ⑤ 채택 | 수정 |
| 2026-11-18 19:5x | 전체 `cleanTest test --continue`(단독 실행): 14클래스 186/0/0(blind 162 + impl 23 + 앱 1) · red 확인: 사용 대상 정렬을 ExpiresAtDesc로 바꾸면 blind 5 · impl 2 실패 → 원복(grep Desc 0) | green |
| 2026-11-18 20:0x | 로컬 스모크(임시 postgres:16 127.0.0.1:25433 · bootJar · interval 2s · scratchpad/ledger/smoke.py): 적립 1000(30일) · 500(10초) → 사용 700 = B 500 전부 + A 200(만료 임박 순) → 사용 취소 200(USE_CANCEL 적립 200 · 365일) → 적립 A 취소(남은 800만 소멸) → 만료 배치 B EXPIRED amount 0 · 잔액 200 = 지갑 = Σ ACTIVE remaining · 이력 6행 balance_after 1000 · 1500 · 800 · 1000 · 200 · 200 · ERROR 0 | 스모크 통과 |
| 2026-11-18 20:0x | 재현 스모크(race.py · 가정 2): ① 잔액 1000에 1000 사용 2건 동시 100회 → **100/100 둘 다 201**(사용 합 2000 · 지갑 0 = 적립 잔여 0 — 내부 합은 맞아 보임) ② 100 사용 10건 동시 30회 → 30/30 사용 합 1000인데 지갑 900(잃어버린 갱신 · '1000 − 사용 합 ≠ 잔액' 30) · 지갑 ≠ Σ remaining 1/30 ③ 동시 첫 적립 2건 100회 → **100/100 지갑 2개** · 조회 지갑 잔액 100(≠ 200) · ERROR 0 · 정리(JVM 0 · 컨테이너 0 — pkill이 자기 셸까지 끝내 정리 명령을 따로 다시 실행) | 가정 2 실증 |
| 2026-11-18 19:4x | 리뷰 루프 1(codex high: medium 4 · low 2 ∥ Opus 워커 183,445 토큰: medium 1 · low 8 · high 0) — 미러 scratchpad/ledger-rv/mirror · 보안 스캔 0 · 종합 LR1-1~13 | 수정 착수 |
| 2026-11-18 19:59 | 루프 1 코드 수정(구현 워커 재개 · 163,980 토큰): 지갑 없는 적립 취소 · 만료 = 지갑 생성 후 차감 · ref 제어 문자 400 · now 한 번 정규화 · expiresAt 상한 9999-12-31T23:59:59Z · 배치 꽉 차면 WARN · 테스트 +7(한 트랜잭션 롤백 spy · 스케줄러 등록 enabled true/false · 경계) · 전체 193/0 | 메인 수정 |
| 2026-11-18 20:0x | 메인 수정: blind USE_CANCEL ref_id = 사용 건 id로 좁힘 · PointWallet KDoc ① 문구 · ADR §3(지갑 없는 건 · WARN) · §6.1(오프셋 필수 · 상한 · 마이크로초 비교 · 제어 문자) · test-contract 406 본문 미보장 | 문서 |
| 2026-11-18 20:1x | 동시 취소 재현(race2.py · LR1-13): ① 사용 600 전체 취소 2건 동시 100회 → 100/100 둘 다 200 · 환원 적립 2건(합 1200 > 600) · canceled_amount 600 덮어씀 · 지갑 1000 ≠ Σ ACTIVE remaining 1600(100/100) ② 적립(남은 600) 취소 2건 동시 100회 → 100/100 둘 다 200 · EARN_CANCEL 이력 2행 · 지갑 0(덮어쓰기로 맞아 보임) · ERROR 0 · 정리(JVM 0 · 컨테이너 0) → ADR §8 행 추가 | 재현 |
| 2026-11-18 20:3x | 리뷰 루프 2(codex high: medium 1 · low 2 ∥ Opus 워커 174,114 토큰: medium 1 · low 6 · high 0) — 종합 LR2-1~10 · 채택 9 · 기각 1 · 코드 수정 구현 워커 재개 · ADR §3(지갑 없음 보호 범위 · 환원 만료 상한) · §6.1(ref 문자열만 · 코드 포인트 · 서로게이트) | 수정 착수 |
| 2026-11-18 20:2x | 루프 2 코드 수정(구현 워커 재개): 환원 만료 상한 자름 · 로그 커밋 뒤(스케줄러) · 문자열 필드 숫자/불리언 400 · 짝 없는 서로게이트 400 · 테스트 구간 분리 · 실패 주입 대상 단언 · 코드 포인트 64/65 · 500ns · 500 INTERNAL_ERROR HTTP 시험 · 워커 199/0 → 메인 단독 재실행 확인 | 루프 3 |
| 2026-11-18 20:2x | 리뷰 루프 3(codex high: medium 1 · low 3 ∥ Opus 워커 197,851 토큰: medium 1 · low 3 · high 0) — 종합 LR3-1~7 · 채택 6 · 기각 1 | 수정 |
| 2026-11-18 20:3x | 루프 3 수정(구현 워커 재개 · 191,986 토큰): 모르는 필드 400 · valid-days 1~36500 · consume 검사 순서 · 스케줄러 단위 테스트 · LedgerProperties 바인딩 테스트 · 메인: blind 오류 본문 필드 집합 정확 비교 · ADR §3 · §6.1 · 전체 207/0(메인 재실행) | post-fix 재점검 |
| 2026-11-18 20:3x | post-fix 재점검(codex medium): 신규 low 1(남은 금액 0인 ACTIVE 건 consume이 상태 오류) → 메인 수정(상태 · 만료 검사와 남은 금액 검사 분리 + 도메인 테스트 1) · 전체 208/0 · 3루프 상한 도달 · 남은 open 0 | 리뷰 마감 |
| 2026-11-18 20:4x | ADR-000 채택 · §9 검증 결과 · NEXT · measurement-log · code · docs 커밋 | 마감 |

## 리뷰 ledger

| id | 루프 | 출처 | 위치 | 판정 | 상태 |
|----|------|------|------|------|------|
| LR1-1 | 1 | codex · opus | ExpirePointsService.kt:35-43 · ExpiryScheduler.kt | 채택 — 지갑 없는 적립 건이 배치 전체를 막음 → 지갑 생성 후 차감(적립 취소도) | fixed |
| LR1-2 | 1 | codex · opus | PointRules.kt:23-26 | 채택 — NUL 등 제어 문자 → 400 | fixed |
| LR1-3 | 1 | opus | 서비스 now · PointEarn.kt:73,92 | 채택 — now를 서비스에서 한 번 정규화(쿼리 · 도메인 같은 값) | fixed |
| LR1-4 | 1 | codex | PointEarn.kt:106-113 · PointRules.kt:28-33 | 기각 — 마이크로초 미만 미래 시각 거부: 저장 정밀도 기준 판정이 일관 · ADR §6.1에 규칙 명시 | rejected |
| LR1-5 | 1 | codex | ExpiryBlindTest · ExpiryIntegrationTest | 채택 — 배치 한 트랜잭션 롤백 시험(spy) | fixed |
| LR1-6 | 1 | codex · opus | UseCancelBlindTest.kt:50 | 채택 — ref_id = 사용 건 id 단언 | fixed |
| LR1-7 | 1 | codex | ADR-000:3 | 채택 — 상태 채택은 마감 커밋에서 | fixed |
| LR1-8 | 1 | opus | PointRequests.kt:63-70 | 채택 — expiresAt 상한 | fixed |
| LR1-9 | 1 | opus | test-contract §3 | 채택 — 406 본문 미보장으로 계약 수정 | fixed |
| LR1-10 | 1 | opus | ExpiryScheduler.kt · 테스트 설정 | 채택 — 스케줄러 등록 시험 | fixed |
| LR1-11 | 1 | opus | ExpirePointsService.kt | 채택 — 배치 꽉 차면 WARN | fixed |
| LR1-12 | 1 | opus | ADR §6.1 | 채택 — 오프셋 필수 명시 | fixed |
| LR1-13 | 1 | opus | ADR §8 · CancelUseService · CancelEarnService | 채택 — 동시 취소 약점 재현 · 수치 추가 | fixed |
| LR2-1 | 2 | opus | impl/BoundaryIntegrationTest.kt:28-36,61,68 · ExpiryRollbackIntegrationTest.kt:29-45 | 채택 — 테스트 순서 의존 · 실패 주입 대상 단언 | fixed |
| LR2-2 | 2 | codex | PointEarn.kt:117 | 채택 — 환원 적립 만료 시각 상한으로 자름 | fixed |
| LR2-3 | 2 | opus | PointWallet.kt:62,69 · ExpirePointsService.kt | 채택(문서) — 지갑 없음 외 DB 조작 행은 배치를 막음 · ADR §3 보호 범위 명시 | fixed |
| LR2-4 | 2 | opus | ExpirePointsService.kt:49-53 | 채택 — 로그를 커밋 뒤(스케줄러)로 | fixed |
| LR2-5 | 2 | opus | JacksonConfig.kt:18-23 | 채택 — 문자열 필드 숫자 · 불리언 400 | fixed |
| LR2-6 | 2 | opus | PointRules.kt:27-29 | 채택 — 짝 없는 서로게이트 400 | fixed |
| LR2-7 | 2 | opus | 길이 · 500ns 경계 | 채택 — 코드 포인트 · 500ns 테스트 | fixed |
| LR2-8 | 2 | opus | ApiExceptionHandler.kt:37 | 채택 — 406 주석 | fixed |
| LR2-9 | 2 | codex | ApiExceptionHandler.kt:40 | 채택 — 500 INTERNAL_ERROR HTTP 시험 | fixed |
| LR2-10 | 2 | codex | test-contract.md:3 | 기각 — 미러에서 spec.md로 복사한 탓 · 실제 경로 requirement-spec.md가 맞음 | rejected |
| LR3-1 | 3 | opus | JacksonConfig.kt:15-29 · PointController.kt:46-48 | 채택 — 모르는 필드 400(오타 키가 전부 취소로 바뀌던 것) | fixed |
| LR3-2 | 3 | codex · opus | LedgerProperties.kt:23-28 · CancelUseService.kt:46 | 채택 — valid-days 상한 기동 검사 | fixed |
| LR3-3 | 3 | codex | PointEarn.kt:76-79 | 채택 — consume 검사 순서 | fixed |
| LR3-4 | 3 | codex | blind/BlindTestSupport.kt:273-278 | 채택 — 오류 본문 필드 집합 정확 비교 | fixed |
| LR3-5 | 3 | opus | CancelEarnService.kt:36-37 · CancelUseService.kt:47-48 | 채택(문서) — 데이터 이상 행이 400으로 보이는 경우 ADR §3 명시 | fixed |
| LR3-6 | 3 | opus | ExpiryScheduler.kt:22-35 | 채택 — 스케줄러 단위 테스트(예외 삼킴 · WARN) | fixed |
| LR3-7 | 3 | codex | test-contract.md:3 | 기각 — 미러 복사 탓(LR2-10과 같음) | rejected |
| LR3-P1 | post-fix | codex | PointEarn.kt:78 | 채택 — 남은 금액 0인 건은 금액 오류로(검사 분리) | fixed |

## 생략한 검증

- (없음)

## 완료 요약 (2026-11-18)

- 포인트 원장 베이스라인: 지갑 잔액 컬럼 + 적립 건별 남은 금액 UPDATE · 만료 임박 순 차감 · 사용 취소 = 새 적립 · 적립 취소 = 남은 만큼만 · 만료 배치(한 트랜잭션 · batch-size) · API 7종 · 락 · 제약 없음(일부러).
- 검증: 208/0(계약 162 · 구현 45 · 기동 1) · red 확인 · 로컬 스모크 · 재현 스모크 2회(동시 사용 · 첫 적립 · 동시 취소) · 듀얼 리뷰 3루프 + post-fix 재점검(마지막 신규 low 1 → 수정).
- 메인 판정으로 바꾼 설계: 적립 취소 · 만료 차감은 잔액으로 거르지 않음(음수 허용 — 배치가 한 사용자 때문에 멈추지 않게) · 지갑 없는 건은 지갑을 만들어 뺀다.
- CS 이슈 아카이브: 보류(study-note 쓰기는 사용자 확인 — 후보: '내부 합계가 맞아 보이는 이중 사용' · '같은 값 덮어쓰기로 맞아 보이는 이중 소멸').


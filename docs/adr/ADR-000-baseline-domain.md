# ADR-000: 포인트 도메인과 평범한 구현 (baseline)

- 상태: 채택 (Accepted) — 2026-11-18 구현 · 검증 끝
- 날짜: 2026-11-18
- 성격: **베이스라인**. 정합성 장치 없이 "기능이 되는" 가장 평범한 포인트 구현을 만든다. ADR-001부터는 이 베이스라인에서 문제를 관측하고 하나씩 해결한다.
- 아키텍처: 레이어드(api / service / domain) + DDD 애그리거트
- 인터뷰 기록: `docs/plans/2026-11-18/adr-000-baseline/interview.md`

## 1. 왜 베이스라인부터 만드는가

README 아키텍처(Insert-only `point_event` + 적립 건별 `point_detail` + 큐 + DLQ + Redis 잔액 캐시)는 이미 **해법**이다. 해법부터 만들면 "왜 Insert-only 원장인가"가 남의 글에서 빌린 추론으로만 남는다. 이 랩은 seat-reservation-lab · payment-consistency-lab과 같은 순서를 따른다.

```
ADR-000 평범한 구현 ──▶ 동시 요청 · 순서 역전 · 대량 만료로 문제 관측 ──▶ ADR-00X: 선택지별 실험 · 실측 ──▶ 해결 확인
```

베이스라인 원칙:

- **평범할 것**: 처음 포인트를 만드는 개발자가 짜는 코드. 잔액 컬럼 하나와 적립 건마다 "남은 금액" 컬럼을 두고 `@Transactional` 안에서 **조회 → 애플리케이션에서 계산 → UPDATE**. 정상 경로에서는 올바르게 동작한다.
- **문제를 가리지 않을 것**: 락 · 낙관적 버전 · 유니크 제약 · CHECK 제약 · Insert-only 원장 · 큐 · 멱등키를 넣지 않는다. 스키마에는 PK만 둔다(FK도 두지 않는다 — §5).
- **모두 동기**: 적립 · 사용 · 취소 · 만료가 모두 REST 요청 하나(또는 배치 한 번) 안에서 끝난다. 비동기 적립과 순서 역전(Q5)은 큐를 도입하는 ADR에서 다룬다.

## 2. 도메인 모델

```
point_wallet (지갑)           ← 사용자당 1개(설계상) · 잔액 컬럼
point_earn   (적립 건)        ← 적립 1건 · 남은 금액 · 유효기간
point_use    (사용 건)        ← 사용 1건 · 취소 누적액
point_history (이력 로그)     ← 모든 변화 1건마다 1행 · 변화 뒤 잔액 기록
```

| 결정 | 내용 | 이유 |
|------|------|------|
| 잔액을 **저장**한다 | `point_wallet.balance`를 매 변화마다 UPDATE | 가장 직관적이고 조회가 싸다. 잔액과 적립 건 합이 서로 달라지는 문제(Q6)는 관측 대상 |
| 적립 건에 **남은 금액** 컬럼 | 사용 · 만료 · 적립 취소가 `remaining`을 UPDATE | 만료 · 소진 순서(Q2)를 표현하려면 적립 건 단위가 있어야 한다. 무엇을 얼마나 뺐는지는 남기지 않는다 |
| 사용-적립 연결을 **남기지 않는다** | 사용 건은 "얼마를 썼다"만 안다 | 평범한 첫 구현. 그래서 사용 취소는 원래 적립 건으로 되돌릴 수 없고 새 적립을 만든다(Q3 · Q4) |
| 이력은 **로그**일 뿐 | `point_history`는 쓰기만 하고 아무도 읽어 계산하지 않는다 | 진실의 원천은 잔액 컬럼과 `remaining`. 이력은 사람이 보는 기록이다(Q6 — 무엇이 진실인가) |
| 지갑 생성 | 첫 적립 때 없으면 만든다(조회 → 없으면 INSERT) | 평범한 lazy 생성. `user_id` 유니크가 없으니 동시 첫 적립에서 지갑이 둘 생길 수 있다(관측 대상) |
| 금액 | 포인트 정수(`Long`) | — |

### 2.1 애그리거트

| 애그리거트 | 루트 | 지키는 불변식 |
|-----------|------|--------------|
| **PointWallet** | `PointWallet` | ① `balance` ≥ 0 — **사용만** 잔액 컬럼으로 먼저 거른다. 적립 취소 · 만료의 차감은 거르지 않는다. 정상 경로에서는 잔액 = Σ `remaining`이라 음수가 되지 않지만, 동시 요청으로 잔액이 이미 틀어졌으면 음수로 남는다(정합성 검증기가 센다 — §8). 여기서 막으면 그 사용자 하나 때문에 만료 배치 실행 전체가 롤백되고, 다음 실행도 같은 건에서 막힌다 |
| **PointEarn** | `PointEarn` | ② 0 ≤ `remaining` ≤ `amount` ③ 상태 `ACTIVE → CANCELED` · `ACTIVE → EXPIRED`만 · `CANCELED` · `EXPIRED`면 `remaining = 0` |
| **PointUse** | `PointUse` | ④ 0 ≤ `canceledAmount` ≤ `amount` ⑤ `CANCELED` ⇔ `canceledAmount = amount` · `PARTIALLY_CANCELED` ⇔ 0 < `canceledAmount` < `amount` |

#### 애그리거트 하나로 지킬 수 없는 규칙

| 규칙 | 걸친 애그리거트 | 베이스라인 처리 | 감수한 것 |
|------|---------------|------------|----------|
| **잔액 = Σ 살아 있는 적립 건 `remaining`** | Wallet, Earn(여럿) | 서비스가 **같은 트랜잭션**에서 지갑과 적립 건을 함께 UPDATE한다 | 동시 요청이 같은 스냅샷을 읽으면 둘 다 계산이 맞는 채로 덮어쓴다(잃어버린 갱신 — Q1). 만료 배치가 돌기 전에는 만료된 `remaining`이 잔액에 남는다(Q6 창) |
| 사용 금액 = 이번에 깎은 `remaining` 합 | Use, Earn(여럿) | 서비스가 만료 임박 순으로 깎는다 | 무엇을 깎았는지 남기지 않는다 |

- 애그리거트가 규칙을 가져도 **동시성 문제는 남는다**. 베이스라인은 막지 않는다 — 막는 방법은 Q1 ADR이 비교한다.

## 3. 상태 전이

```
PointEarn:   ACTIVE ──적립 취소──▶ CANCELED   (remaining → 0, 남은 만큼만 잔액에서 뺀다)
               │
               └────만료 배치───▶ EXPIRED    (remaining → 0, 남은 만큼 잔액에서 뺀다)

PointUse:    USED ──부분 취소──▶ PARTIALLY_CANCELED ──남은 사용액 전부 취소──▶ CANCELED
               │                   ▲   │                                         ▲
               │                   └───┘ 부분 취소                               │
               └──────────────────── 전체 취소 ──────────────────────────────────┘
```

| 동작 | 트리거 | 베이스라인 절차 (한 트랜잭션 · 락 없음) |
|------|--------|-------------------------------------|
| 적립 | `POST /points/earn` | 지갑 조회(없으면 생성) → 적립 건 INSERT(`remaining = amount`) → `balance += amount` → 이력 |
| 사용 | `POST /points/use` | 지갑 조회 → `balance < amount`면 `409 INSUFFICIENT_BALANCE` → `ACTIVE` · `remaining > 0` · `expiresAt > now` 적립 건을 **만료 임박 순(같으면 id 순)**으로 읽어 차례로 `remaining`을 깎는다 → 깎은 합이 모자라면 `409 INSUFFICIENT_BALANCE`(롤백) → `balance -= amount` → 사용 건 INSERT → 이력 |
| 사용 취소 | `POST /points/uses/{useId}/cancel` | 사용 건 조회 → 상태 · 금액 검사 → **새 적립 건** INSERT(`source = USE_CANCEL`, `expiresAt = now + 365일`, 상한 `9999-12-31T23:59:59Z`를 넘으면 상한) → `balance += 취소액` → `canceledAmount += 취소액` → 이력 |
| 적립 취소 | `POST /points/earns/{earnId}/cancel` | 적립 건 조회 → `ACTIVE` 아니면 409 → 소멸액 = `remaining` → `remaining = 0` · `CANCELED` → `balance -= 소멸액` → 이력(소멸액). **이미 쓴 몫은 회수하지 않는다** |
| 만료 | `@Scheduled` 배치 | `ACTIVE` · `expiresAt <= now` 적립 건을 `expiresAt` 순으로 최대 `batch-size`건 읽어 각각 `balance -= remaining` · `remaining = 0` · `EXPIRED` → 이력. **실행 한 번 = 트랜잭션 하나** |

- 만료 판정: `expiresAt <= now`면 만료. 시각은 애플리케이션 `Clock`에서 얻는다(테스트에서 고정 가능).
- 사용은 **잔액 컬럼으로 먼저** 거른다. 만료됐지만 배치가 아직 안 돈 적립 건은 잔액에는 남아 있고 사용 대상에서는 빠진다 — 그 사이 "잔액은 충분한데 사용이 409"가 날 수 있다(Q6 창 — 베이스라인의 모습).
- 이력은 상태가 바뀔 때마다 1행 남긴다. 이미 다 쓴 적립 건(`remaining = 0`)의 취소 · 만료는 `amount = 0` 이력이 된다.
- 지갑이 없는 적립 건(DB를 직접 조작했을 때만 생긴다)을 적립 취소 · 만료하면 그때 지갑을 만들어 뺀다. 잔액은 음수로 남는다(§2.1 ①). 배치가 그 건 때문에 멈추지 않게 하려는 것이다. 이 보호는 지갑이 없는 경우에만 해당한다. DB를 직접 조작해 정상 경로로는 생길 수 없는 행(예: `remaining`이 음수인 적립 건, `user_id`가 0인 적립 건)을 만들면 그 건에서 배치 실행이 롤백되고, 다음 실행도 같은 건에서 멈춘다(ERROR 로그). 이런 행은 정합성 검증기로 찾는다. 같은 행을 적립 취소 · 사용 취소 API로 건드리면 지갑을 만들다 `user_id` 검사에 걸려 `400 INVALID_REQUEST`로 보인다 — 입력 오류가 아니라 데이터 이상이다.
- 만료 배치 한 번의 대상 수가 `batch-size`와 같으면 WARN 로그를 남긴다(만료가 밀리는 신호).
- 이력의 `ref_id`: `EARN` · `EARN_CANCEL` · `EXPIRE`는 적립 건 id, `USE` · `USE_CANCEL`은 사용 건 id다. 사용 취소로 생긴 환원 적립은 따로 `EARN` 이력을 남기지 않는다(변화 1건 = 이력 1행). 환원 적립 건은 `source_ref` = 사용 건 id로 찾는다.
- 사용 취소로 생긴 적립은 **원래 적립 건의 유효기간과 무관하게** 새 365일을 받는다. 이미 만료된 적립에서 쓴 포인트도 되살아난다(Q4).

## 4. 규칙 · 설정

| 규칙 | 값 | 설정 |
|------|----|------|
| 앱 포트 | 8104 (상위 README) | `server.port` |
| 사용 취소 환원 적립 유효기간 | 365일 | `ledger.restore.valid-days` |
| 만료 배치 주기 | 60초 | `ledger.expiry.interval` |
| 만료 배치 1회 최대 건수 | 1,000 | `ledger.expiry.batch-size` |
| 만료 배치 켜기 | `true`(테스트는 끄고 직접 부른다) | `ledger.expiry.enabled` |

## 5. DB 스키마 (Flyway `V1__init.sql`)

```sql
CREATE TABLE point_wallet (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    balance     BIGINT       NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE point_earn (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    amount       BIGINT       NOT NULL,
    remaining    BIGINT       NOT NULL,
    source       VARCHAR(20)  NOT NULL,   -- EARN · USE_CANCEL
    source_ref   VARCHAR(64)  NOT NULL,   -- 적립 사유 참조 · USE_CANCEL이면 사용 건 id
    status       VARCHAR(20)  NOT NULL,   -- ACTIVE · CANCELED · EXPIRED
    expires_at   TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE point_use (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    amount           BIGINT       NOT NULL,
    canceled_amount  BIGINT       NOT NULL,
    order_ref        VARCHAR(64)  NOT NULL,
    status           VARCHAR(30)  NOT NULL,   -- USED · PARTIALLY_CANCELED · CANCELED
    used_at          TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE point_history (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    type           VARCHAR(20)  NOT NULL,   -- EARN · USE · USE_CANCEL · EARN_CANCEL · EXPIRE
    amount         BIGINT       NOT NULL,   -- 변화량의 크기(0 이상) · 방향은 type이 정한다 · 0 = 상태만 바뀜(다 쓴 적립 건의 취소 · 만료)
    ref_id         BIGINT       NOT NULL,   -- 적립 건 또는 사용 건 id
    balance_after  BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);
```

- 의도적으로 **없는 것**: `point_wallet(user_id)` 유니크, `balance ≥ 0` · `remaining` 범위 CHECK, 낙관적 락 버전, 사용-적립 연결 테이블, 조회용 인덱스(사용의 "만료 임박 순" 조회 포함). 인덱스 부재가 측정을 왜곡하면 해당 ADR에서 V2 이후로 추가하고 before/after를 기록한다.
- 같은 `user_id` 지갑이 둘 이상이면 베이스라인은 **가장 작은 id**를 쓴다(조회 규칙만 정하고 막지 않는다).

## 6. API

오류 본문은 `{code, message}`. 인증은 범위 밖 — 사용자는 요청 본문 · 경로로 받는다.

| 메서드 | 경로 | 요청 | 성공 | 실패 |
|--------|------|------|------|------|
| POST | `/points/earn` | `{userId, amount, expiresAt, sourceRef}` | `201` 적립 건 | `400 INVALID_REQUEST` |
| POST | `/points/use` | `{userId, amount, orderRef}` | `201` 사용 건 | `400` · `409 INSUFFICIENT_BALANCE`(지갑 없음 포함) |
| POST | `/points/uses/{useId}/cancel` | `{cancelAmount?}` (생략 · `null` = 남은 사용액 전부) | `200` 사용 건 | `400` · `404 USE_NOT_FOUND` · `409 USE_NOT_CANCELABLE`(`CANCELED`) · `409 CANCEL_AMOUNT_EXCEEDED` |
| POST | `/points/earns/{earnId}/cancel` | — | `200` 적립 건 | `400` · `404 EARN_NOT_FOUND` · `409 EARN_NOT_CANCELABLE`(`ACTIVE` 아님) |
| GET | `/points/users/{userId}/balance` | — | `200 {userId, balance}` (지갑 없으면 0) | `400` |
| GET | `/points/users/{userId}/history?limit=` | `limit` 1~100, 기본 50 | `200 [이력]` 최신순(id 내림차순) | `400` |
| GET | `/points/earns/{earnId}` · `/points/uses/{useId}` | — | `200` | `404` |

- 적립 건 응답: `{earnId, userId, amount, remaining, source, sourceRef, status, expiresAt, createdAt}`.
- 사용 건 응답: `{useId, userId, amount, canceledAmount, orderRef, status, usedAt}`.
- 이력 응답: `{historyId, type, amount, refId, balanceAfter, createdAt}`.

### 6.1 입력 검증 (위반 = `400 INVALID_REQUEST` · 상태 불변)

| 필드 | 규칙 |
|------|------|
| `userId` | JSON 정수 · 1 이상 |
| `amount` · `cancelAmount` | JSON 정수만(1.5 · 문자열 = 400) · 1 이상 · **100,000,000 이하**(요청 1건 상한 — `Long` 잔액이 넘치지 않게) |
| `expiresAt` | ISO-8601 시각, 오프셋 또는 `Z` 필수(오프셋 없는 지역 시각 · 숫자 = 400) · **지금보다 뒤** · `9999-12-31T23:59:59Z` 이하. 비교와 저장은 마이크로초 단위다(DB `TIMESTAMPTZ` 정밀도 — 그보다 작은 자리는 버린 뒤 비교한다) |
| `sourceRef` · `orderRef` | JSON 문자열만(숫자 · 불리언 = 400) · 공백 아님 · 1~64자(코드 포인트 기준) · 제어 문자(NUL · 탭 · 줄바꿈 등)와 짝 없는 서로게이트 없음 |
| 경로 id | 양의 정수 · Long 범위 |
| `limit` | 정수 1~100 |
| 본문 | JSON으로 읽을 수 없으면 400 · 모르는 필드가 있으면 400(오타 키가 '생략 = 전부 취소'로 바뀌지 않게) |

- 판정 순서(앞 단계가 우선한다): 입력 검증(400) → 존재(404) → 상태(409 `*_NOT_CANCELABLE`) → 금액(409).

## 7. 코드 구조

```
com.jun.labs.ledger
├── api/                 컨트롤러 · 요청/응답 DTO · 예외 → HTTP 매핑 · 만료 스케줄러(진입점)
├── service/             UseCase 인터페이스 · 커맨드/결과
│   └── impl/            UseCase 구현 — 조회 → 도메인 메서드 → 저장 (조율만)
└── domain/              PointWallet · PointEarn · PointUse · PointHistory · 상태 enum · 도메인 예외(ErrorCode)
    └── repository/      애그리거트 루트별 Spring Data JPA
```

- 의존 방향: `api → service → domain`. 쿼리는 파생 쿼리 메서드만(정렬 포함 — `findBy…OrderByExpiresAtAscIdAsc`).
- 패키지는 repo 골격 그대로 `com.jun.labs.ledger`(메인 `LedgerApplication`).

## 8. 베이스라인이 일부러 남겨 둔 약점 → 가설 ADR

번호는 관측 순서대로 붙인다(ADR-001만 먼저 정한다).

| 약점 (베이스라인에서 예상되는 모습) | README Q | 가설 ADR |
|------|---|---------|
| 합격 기준이 없다 — README Consistency Checks를 셀 도구가 없다. 베이스라인에서 셀 수 있는 것: 잔액 = Σ 적립 잔여 · 음수 잔액 · 만료 잔여 · 사용자 단위 '환원 합 ≤ 사용 합'. **셀 수 없는 것**: 원 적립 건별 초과 환원(사용-적립 연결 없음 → Q3 ADR 뒤) · DLQ 미처리(큐 없음 → Q5 ADR 뒤) — 정합성 검증기는 이 둘을 0으로 통과시키지 않고 **'판정 불가'**로 표시한다 | 전체 | **ADR-001** 정합성 판정 · 관측 하네스 |
| 같은 사용자의 동시 사용 → 잃어버린 갱신. 재현(2026-11-18, 로컬): ① 잔액 1,000P에 1,000P 사용 2건을 동시에 100회 보내자 **100회 모두 둘 다 성공**했다(1,000P로 2,000P 사용). 지갑 잔액과 적립 잔여가 둘 다 0이라 우리 쪽 합계는 맞아 보인다 — 사용 합 > 적립 합으로만 드러난다 ② 100P 사용 10건 동시 30회: 30회 모두 1,000P가 다 쓰였는데 지갑 잔액은 900P로 남았다. 지갑 잔액 ≠ 적립 잔여 합은 30회 중 1회뿐이었다 ③ 동시 첫 적립 2건 100회: **100회 모두 지갑 2개**, 조회에 쓰는 가장 작은 id 지갑의 잔액은 200이 아니라 100 | Q1 | 동시 차감 제어(행 락 · 조건부 UPDATE · 낙관적 락 비교) · 지갑 유일성 |
| 같은 사용 건 · 같은 적립 건의 동시 취소. 재현(2026-11-18, 로컬, 각 100회): ① 600P 사용 건에 전체 취소 2건을 동시에 보내자 **100회 모두 둘 다 성공**했다. 환원 적립이 600P짜리로 2건 생겨 환원 합 1,200P가 사용액 600P를 넘고, 사용 건의 `canceled_amount`는 600P로 덮어써졌다. 지갑 잔액은 1,000P(갱신 하나 유실)인데 살아 있는 적립 잔여 합은 1,600P라 100회 모두 서로 달랐다 ② 남은 금액 600P인 적립 건에 취소 2건을 동시에 보내자 100회 모두 둘 다 성공해 `EARN_CANCEL` 이력이 2행씩 남았다. 지갑 잔액은 두 요청이 같은 값(600 − 600 = 0)을 덮어써 0으로 맞아 보이고, 이력에만 두 번 소멸한 흔적이 남는다 | Q1 · Q6 | 동시 차감 제어와 같은 ADR에서(취소도 같은 행을 읽고 덮어쓴다) |
| 만료 임박 순 외의 정책을 비교할 근거가 없다 | Q2 | 소진 순서 정책 |
| 사용 취소가 새 적립을 만든다 → 원래 적립 건 · 유효기간을 잃는다 | Q3 | 사용-적립 연결(적립 건별 차감 내역)과 원 건 환원 |
| 이미 만료된 적립에서 쓴 포인트가 취소로 365일짜리로 되살아난다 | Q4 | 만료된 적립 건 환원 정책 |
| 모두 동기라 순서 역전이 없다 — 비동기 적립 · 취소를 들이는 순간 생긴다 | Q5 | 큐 · 재시도 · DLQ 도입과 순서 역전 |
| 잔액 컬럼과 적립 건 합 · 이력이 불일치가 생겨도 무엇이 진실인지 정할 수 없다 · 만료 배치 전 창 | Q6 | Insert-only 원장(이력이 진실) · 잔액은 파생 |
| 일부 사용된 적립 취소 → 쓴 몫을 회수하지 못한다 | (Q6 연관) | 적립 취소 회수 정책 |
| 만료 배치가 한 트랜잭션 · 인덱스 없음 → 하루 100만 건 만료에서 시간 · 잠금 | (Load Profile) | 대량 만료 배치 |

## 9. 검증

- 계약 테스트(설계 문서와 API 계약만 보고 구현과 따로 쓴 테스트) · 구현 테스트 · 로컬 스모크 · 재현 스모크 · 듀얼 리뷰 3회. 자세한 기록은 `docs/plans/2026-11-18/adr-000-baseline/log.md`.

### 9.1 결과 (2026-11-18)

- 테스트: 208개 모두 통과(계약 테스트 162 · 구현 테스트 45 · 앱 기동 1). 사용 차감 순서를 일부러 뒤집으면 계약 테스트 5개와 구현 테스트 2개가 실패하는 것을 확인했다.
- 로컬 스모크(임시 PostgreSQL 16 · 실행 jar): 적립 1,000(30일) · 500(10초) → 700 사용은 만료가 임박한 500을 먼저 다 깎고 1,000에서 200을 깎았다 → 사용 취소 200(새 적립 200 · 365일) → 1,000 적립 취소(남은 800만 소멸) → 만료 배치가 다 쓴 500 적립을 금액 0 이력으로 닫았다. 지갑 잔액 200 = 살아 있는 적립 잔여 합 200이고, 이력 6행의 변화 뒤 잔액이 모두 맞았다.
- §8 약점이 실제로 드러나는지: §8 표의 수치(동시 사용 · 동시 첫 적립 · 동시 취소, 각 30~100회)가 이 재현 결과다. 지갑 잔액과 적립 잔여 합만 비교하면 동시 사용 2건(1,000P로 2,000P 사용)과 동시 적립 취소(이력 두 번 소멸)를 놓친다. 정합성 검증기(ADR-001)는 사용 합 · 환원 합 · 이력도 함께 세야 한다.
- 리뷰에서 고친 것: 지갑 없는 적립 건 · 잔액이 틀어진 사용자 하나 때문에 만료 배치 전체가 멈추던 것, 제어 문자 · 짝 없는 서로게이트 · 숫자를 문자열 자리에 보낸 요청이 500이던 것, 모르는 필드가 '전부 취소'로 바뀌던 것, 현재 시각을 쿼리와 도메인에 다른 정밀도로 넘기던 것, 만료 시각 · 설정값 상한, 만료 로그를 커밋 뒤로 옮김, 테스트 공백(배치 한 트랜잭션 롤백 · 스케줄러 등록 · 500 응답 · 테스트끼리 시간 구간이 겹치던 것).


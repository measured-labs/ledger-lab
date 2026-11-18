-- ADR-000 §5 베이스라인 스키마 — PK만 둔다. user_id 유니크 · CHECK · 버전 · 인덱스는 일부러 없다(관측 대상)

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
    amount         BIGINT       NOT NULL,   -- 변화량의 크기(0 이상) · 방향은 type이 정한다 · 0 = 상태만 바뀜
    ref_id         BIGINT       NOT NULL,   -- 적립 건 또는 사용 건 id
    balance_after  BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

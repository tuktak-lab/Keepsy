-- V4 예측 · 예측 스냅샷 (Keepsy #9)
-- 기준: 요구사항명세서 5장. enum 값은 docs/CONTRACTS.md 3장, 문자열로 저장한다.
-- 예측 상태(예정 · 주기 도래 등)는 저장하지 않고 조회할 때 오늘 날짜로 계산한다 (A12, FR-PRD-04).

-- 고객 × 시술 카테고리별 현재 예측. 재계산은 이 행을 갱신(upsert)만 하고 처리 표시 칸은 건드리지 않는다 (F2).
-- 카테고리 방문이 모두 주기 계산 제외가 되면 행을 지우지 않고 예측 칼럼을 비운다 = 예측 없음 (FR-PRD-01 ①).
-- 고객 삭제(파기) 때는 이 고객의 행을 지운다 (FR-CUS-10).
CREATE TABLE customer_prediction (
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id                  BIGINT      NOT NULL,
    customer_id              BIGINT      NOT NULL,
    category                 VARCHAR(20) NOT NULL COMMENT 'NAIL | LASH | WAXING | SEMI_PERMANENT | ETC',
    last_service_date        DATE        NULL COMMENT '해당 카테고리 기준 마지막 시술일 (주기 계산 제외 방문은 뺌). NULL이면 예측 없음',
    basis_visit_count        INT         NOT NULL DEFAULT 0 COMMENT '근거 방문 수 (주기 계산 제외 방문은 세지 않음)',
    avg_interval_days        INT         NULL COMMENT '최근 5개 간격 평균(반올림). 방문 2회 이하면 기본 주기 최대값',
    start_date               DATE        NULL COMMENT '예측 시작일',
    end_date                 DATE        NULL COMMENT '예측 종료일',
    end_signal               BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '참이면 시작일 = 종료일 = 종료 신호 방문일 (A7 보완)',
    handling_mark            VARCHAR(20) NULL COMMENT 'CONTACTED | ON_HOLD | CLOSED (FR-PRD-07)',
    handling_marked_at       DATETIME    NULL COMMENT '처리 표시 시각 (F2)',
    handling_base_visit_date DATE        NULL COMMENT '처리 기준 방문일. 기준 마지막 방문일이 이보다 늦어지면 자동 해제 (F2)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_prediction_customer_category (customer_id, category),
    KEY idx_customer_prediction_shop_end (shop_id, end_date),
    CONSTRAINT fk_customer_prediction_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_customer_prediction_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

-- 예측을 만들 때마다 쌓는 기록. 예측 정확도 통계용이라 고객을 삭제해도 남긴다 (FR-PRD-08, FR-CUS-10, B8).
-- 해당 카테고리의 다음 방문(주기 계산 제외가 아닌 방문)이 생기면 실제 방문일과 적중 여부를 채운다.
-- 기간이 끝났는데 비어 있으면 빗나감이다. 평가는 기간이 끝난 것만 한다 (FR-ANL-07).
CREATE TABLE prediction_snapshot (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id           BIGINT      NOT NULL,
    customer_id       BIGINT      NOT NULL,
    category          VARCHAR(20) NOT NULL COMMENT 'NAIL | LASH | WAXING | SEMI_PERMANENT | ETC',
    created_at        DATETIME    NOT NULL,
    start_date        DATE        NOT NULL,
    end_date          DATE        NOT NULL,
    basis_visit_count INT         NOT NULL COMMENT '근거: 예측에 쓴 방문 수',
    actual_visit_date DATE        NULL COMMENT '실제 다음 방문일. 평균 오차(예측 중앙일과의 차이) 계산용 (FR-ANL-07)',
    hit               BOOLEAN     NULL COMMENT '실제 다음 방문이 기간 안이면 참. 다음 방문 전에는 NULL',
    PRIMARY KEY (id),
    KEY idx_prediction_snapshot_customer_category (customer_id, category, id),
    KEY idx_prediction_snapshot_shop_end (shop_id, end_date),
    CONSTRAINT fk_prediction_snapshot_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_prediction_snapshot_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

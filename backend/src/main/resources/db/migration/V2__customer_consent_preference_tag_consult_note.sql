-- V2 고객 · 동의 · 취향 태그 · 상담 메모 (Keepsy #7)
-- 기준: 요구사항명세서 5장. enum 값은 docs/CONTRACTS.md 3장, 문자열로 저장한다.
-- 고객 삭제(파기)는 행을 남기고 식별 정보를 NULL로 비운다 (FR-CUS-10, C12). 그래서 그 칸들은 NULL을 허용하고,
-- 정상 고객의 필수 값(이름 · 휴대폰)은 서버에서 검증한다.

CREATE TABLE customer (
    id                         BIGINT        NOT NULL AUTO_INCREMENT,
    shop_id                    BIGINT        NOT NULL,
    name                       VARCHAR(50)   NULL COMMENT '삭제되면 NULL (C12)',
    phone                      VARCHAR(20)   NULL COMMENT '매장 안 고유. 삭제되면 NULL이라 고유 제약에서 빠짐 (C12, ARCHITECTURE 5장)',
    -- 전화번호 뒷자리 4자리 검색용 (FR-CUS-02, NFR-01). 앱은 쓰지 않고 DB가 채운다
    phone_last4                CHAR(4)       GENERATED ALWAYS AS (RIGHT(phone, 4)) STORED,
    gender                     VARCHAR(20)   NULL COMMENT '선택 (FR-CUS-01). 값은 고객 등록(#34)에서 CONTRACTS에 정함',
    birth_year                 INT           NULL,
    preferred_language         VARCHAR(20)   NULL,
    acquisition_channel        VARCHAR(20)   NULL COMMENT '유입 경로 INSTAGRAM | NAVER_SEARCH | REFERRAL | PASSING_BY | ETC',
    service_caution            VARCHAR(1000) NULL COMMENT '시술 주의사항 (FR-CUS-05)',
    health_caution             VARCHAR(1000) NULL COMMENT '건강 주의사항. 건강 정보 동의 고객만, 철회 시 즉시 삭제 (FR-CUS-05 · 09, D7)',
    preference_memo            VARCHAR(1000) NULL COMMENT '취향 자유 메모 (FR-CUS-04)',
    registered_at              DATETIME      NOT NULL,
    last_visit_date            DATE          NULL COMMENT '방문이 바뀔 때 갱신. 보관 기간 기준 (FR-CUS-12)',
    reservation_restricted     BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '예약 제한 (FR-CUS-14)',
    reservation_restrict_reason VARCHAR(200) NULL,
    status                     VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DELETED (C12)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_shop_phone (shop_id, phone),
    KEY idx_customer_shop_name (shop_id, name),
    KEY idx_customer_shop_phone_last4 (shop_id, phone_last4),
    KEY idx_customer_shop_last_visit (shop_id, last_visit_date),
    CONSTRAINT fk_customer_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
);

-- 동의 이력. 고치지 않고 행을 쌓으며, 항목별 최신 행(id가 가장 큰 행)이 현재 상태 (5장 비고, E8)
CREATE TABLE consent (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id         BIGINT      NOT NULL,
    customer_id     BIGINT      NOT NULL,
    item            VARCHAR(20) NOT NULL COMMENT 'PRIVACY | PHOTO | MARKETING | NIGHT_AD | HEALTH',
    agreed          BOOLEAN     NOT NULL,
    recorded_at     DATETIME    NOT NULL,
    staff_id        BIGINT      NULL COMMENT '처리 스탭. 가상 휴대폰 [수신거부]처럼 스탭 없이 들어오면 비움 (FR-CMP-11)',
    method          VARCHAR(20) NULL COMMENT 'TABLET | PAPER | VERBAL | IMPORT. 스탭 없이 들어온 철회면 비움',
    withdrawal_path VARCHAR(20) NULL COMMENT 'UNSUBSCRIBE | DIRECT. 철회일 때만 (FR-CMP-02, F19)',
    PRIMARY KEY (id),
    KEY idx_consent_customer_item (customer_id, item, id),
    CONSTRAINT fk_consent_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_consent_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT fk_consent_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
);

-- 카테고리별 취향 태그. 매장 안에서 재사용하므로 목록은 (shop_id, category)로 중복 없이 조회 (FR-CUS-04, F3)
CREATE TABLE preference_tag (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT      NOT NULL,
    customer_id BIGINT      NOT NULL,
    category    VARCHAR(20) NOT NULL COMMENT 'NAIL | LASH | WAXING | SEMI_PERMANENT | ETC',
    tag         VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_preference_tag_customer (customer_id, category, tag),
    KEY idx_preference_tag_shop_category (shop_id, category, tag),
    CONSTRAINT fk_preference_tag_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_preference_tag_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

-- 상담 메모. CSV 메모 열도 여기에 저장한다 (F5)
CREATE TABLE consult_note (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT        NOT NULL,
    customer_id BIGINT        NOT NULL,
    staff_id    BIGINT        NOT NULL COMMENT '작성 스탭 = 현재 근무자 (FR-CUS-06)',
    content     VARCHAR(2000) NOT NULL,
    written_at  DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_consult_note_customer (customer_id, written_at),
    CONSTRAINT fk_consult_note_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_consult_note_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT fk_consult_note_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
);

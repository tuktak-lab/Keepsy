-- V1 매장 · 스탭 · 휴무일 · 시술 메뉴 (Keepsy #6)
-- 기준: 요구사항명세서 5장. enum 값은 docs/CONTRACTS.md 3장, 문자열로 저장한다.
-- 펼치는 방식: 값이 정해진 칸(요일별 영업시간, 카테고리별 기본 주기)은 shop 컬럼(5장 비고 "설정은 shop 컬럼으로 충분"),
--             enum 집합(정기 휴무 요일, PIN 잠금 기능 목록)은 쉼표로 이은 enum 이름 문자열.
-- password_reset_token(FR-ACC-06, P2, F14)은 그 기능을 구현할 때 다음 버전으로 더한다.

CREATE TABLE shop (
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    email                      VARCHAR(255) NOT NULL COMMENT '로그인 이메일, 전역 고유',
    password_hash              VARCHAR(100) NOT NULL COMMENT 'BCrypt (NFR-02)',
    name                       VARCHAR(100) NOT NULL,
    phone                      VARCHAR(20)  NULL COMMENT '매장 연락처 (광고 표기에 씀, FR-CMP-04)',
    sender_number              VARCHAR(20)  NULL COMMENT '발신번호 (FR-SET-01)',
    unsubscribe_number         VARCHAR(20)  NULL COMMENT '수신 거부 번호 (FR-SET-01)',

    -- 요일별 영업시간 (FR-SET-01). 시작 · 종료가 모두 NULL이면 그 요일은 정기 휴무
    mon_open_time              TIME         NULL,
    mon_close_time             TIME         NULL,
    tue_open_time              TIME         NULL,
    tue_close_time             TIME         NULL,
    wed_open_time              TIME         NULL,
    wed_close_time             TIME         NULL,
    thu_open_time              TIME         NULL,
    thu_close_time             TIME         NULL,
    fri_open_time              TIME         NULL,
    fri_close_time             TIME         NULL,
    sat_open_time              TIME         NULL,
    sat_close_time             TIME         NULL,
    sun_open_time              TIME         NULL,
    sun_close_time             TIME         NULL,

    -- 설정값 (FR-SET-03·04·06, FR-CMP-05). 기본값은 명세서 3장 · 결정체크리스트 F17
    churn_signal_cap_days      INT          NOT NULL DEFAULT 120 COMMENT '이탈 신호 기간 상한 (FR-SET-03)',
    attribution_days           INT          NOT NULL DEFAULT 14 COMMENT '귀속 기간 (FR-SET-04)',
    daily_send_limit           INT          NOT NULL DEFAULT 500 COMMENT '일 발송 한도 (FR-SET-04)',
    resend_block_days          INT          NOT NULL DEFAULT 7 COMMENT '재발송 제한 기간 (FR-SET-04)',
    retention_years            INT          NOT NULL DEFAULT 3 COMMENT '보관 기간 1~5년 (FR-SET-06, D3)',
    virtual_failure_percent    INT          NOT NULL DEFAULT 0 COMMENT '가상 발송 무작위 실패 비율 % (FR-CMP-10, F17)',
    virtual_unit_price         INT          NOT NULL DEFAULT 0 COMMENT '가상 단가(원). 화면에서 바꾸지 않음 (FR-CMP-05)',
    pin_locked_features        VARCHAR(255) NOT NULL
        DEFAULT 'CONTACT_FULL,CUSTOMER_EDIT,SALES_ANALYTICS,PAYMENT_EDIT,CAMPAIGN_VIEW,CAMPAIGN_SEND'
        COMMENT 'PIN 잠금 기능 키 목록 (CONTRACTS 3장). 기본값은 명세서 2장. OWNER_PIN 등 고정 항목은 넣지 않음',

    -- 카테고리별 기본 주기(일) (FR-SET-02, E5). 기본값은 FR-SET-02 · C16
    nail_cycle_min_days        INT          NOT NULL DEFAULT 21,
    nail_cycle_max_days        INT          NOT NULL DEFAULT 28,
    lash_cycle_min_days        INT          NOT NULL DEFAULT 30,
    lash_cycle_max_days        INT          NOT NULL DEFAULT 60,
    waxing_cycle_min_days      INT          NOT NULL DEFAULT 28,
    waxing_cycle_max_days      INT          NOT NULL DEFAULT 42,
    semi_permanent_cycle_min_days INT       NOT NULL DEFAULT 365,
    semi_permanent_cycle_max_days INT       NOT NULL DEFAULT 730,
    etc_cycle_min_days         INT          NOT NULL DEFAULT 28,
    etc_cycle_max_days         INT          NOT NULL DEFAULT 56,

    -- 로그인 · PIN 실패 잠금 상태 (F14). 서버 재시작 뒤에도 유지
    login_fail_count           INT          NOT NULL DEFAULT 0 COMMENT '연속 실패. 5회면 15분 잠금 (FR-ACC-02, C1)',
    login_locked_until         DATETIME     NULL,
    pin_fail_count             INT          NOT NULL DEFAULT 0 COMMENT '매장 단위 연속 실패. 5회면 5분 차단 (FR-ACC-08, C22)',
    pin_locked_until           DATETIME     NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_shop_email (email)
);

CREATE TABLE staff (
    id                     BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id                BIGINT      NOT NULL,
    name                   VARCHAR(50) NOT NULL,
    pin_hash               VARCHAR(100) NOT NULL COMMENT 'BCrypt. 매장 안 중복은 해시라 서버에서 비교 검사 (5장 비고)',
    owner                  BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '원장 스탭 (FR-ACC-01)',
    locked_feature_allowed BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '잠긴 기능 사용 허용. 원장은 항상 켜짐 (FR-ACC-01·03)',
    regular_days_off       VARCHAR(70) NOT NULL DEFAULT '' COMMENT '정기 휴무 요일. 쉼표로 이은 MONDAY..SUNDAY (FR-SET-07)',
    status                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | INACTIVE (FR-ACC-04)',
    PRIMARY KEY (id),
    KEY idx_staff_shop_status (shop_id, status),
    CONSTRAINT fk_staff_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
);

-- 매장 · 스탭 임시 휴무일 (FR-SET-07). 예약 가능 시간 계산에 쓴다 (FR-RSV-04)
CREATE TABLE day_off (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id  BIGINT       NOT NULL,
    staff_id BIGINT       NULL COMMENT '매장 휴무면 비움',
    off_date DATE         NOT NULL,
    reason   VARCHAR(100) NULL,
    PRIMARY KEY (id),
    KEY idx_day_off_shop_date (shop_id, off_date),
    CONSTRAINT fk_day_off_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_day_off_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
);

CREATE TABLE service_menu (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id              BIGINT       NOT NULL,
    category             VARCHAR(20)  NOT NULL COMMENT 'NAIL | LASH | WAXING | SEMI_PERMANENT | ETC',
    name                 VARCHAR(100) NOT NULL,
    price                INT          NOT NULL COMMENT '원',
    duration_minutes     INT          NOT NULL COMMENT '30분 단위는 서버에서 검증 (FR-SET-02)',
    cycle_min_days       INT          NOT NULL,
    cycle_max_days       INT          NOT NULL,
    prediction_attribute VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL | EXCLUDE | END_SIGNAL (E5)',
    PRIMARY KEY (id),
    KEY idx_service_menu_shop_category (shop_id, category),
    CONSTRAINT fk_service_menu_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
);

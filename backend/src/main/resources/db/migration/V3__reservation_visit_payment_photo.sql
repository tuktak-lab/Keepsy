-- V3 예약 · 예정 시술 · 방문 · 실제 시술 · 결제 · 사진 · 방문 메모 (Keepsy #8)
-- 기준: 요구사항명세서 5장. enum 값은 docs/CONTRACTS.md 3장, 문자열로 저장한다.
-- V1(shop · staff · service_menu, #6)과 V2(customer, #7) 뒤에 적용된다.
-- 귀속 발송 ID(attributed_message_id → campaign_message.id, F15)의 외래 키는 V5에서 ALTER TABLE로 추가한다.

CREATE TABLE visit (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT      NOT NULL,
    customer_id BIGINT      NULL COMMENT '고객 없음으로 마감하면 비움 (E3)',
    visit_date  DATE        NOT NULL,
    source      VARCHAR(20) NOT NULL COMMENT 'NORMAL | IMPORT. 가져온 방문은 귀속 · 매출 제외',
    PRIMARY KEY (id),
    -- 고객 없는 방문(customer_id NULL)은 MySQL 고유 인덱스에서 서로 다른 값으로 보므로 제외된다 (C6)
    UNIQUE KEY uk_visit_customer_date (customer_id, visit_date),
    KEY idx_visit_shop_date (shop_id, visit_date),
    CONSTRAINT fk_visit_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_visit_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

CREATE TABLE reservation (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id              BIGINT       NOT NULL,
    customer_id          BIGINT       NULL COMMENT '고객 없는 예약이면 비움 (E3)',
    staff_id             BIGINT       NOT NULL,
    start_at             DATETIME     NOT NULL,
    end_at               DATETIME     NOT NULL,
    status               VARCHAR(20)  NOT NULL COMMENT 'CONFIRMED | COMPLETED | CANCELED | NO_SHOW',
    channel              VARCHAR(20)  NOT NULL COMMENT '예약 경로 NAVER | KAKAO | PHONE | STORE | ETC',
    consult_first        BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '상담 후 진행 (A13)',
    services_confirmed   BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '상담 후 진행일 때 실제 시술 확인 여부',
    memo                 TEXT         NULL COMMENT '고객 없는 예약은 받지 않음. 고객 삭제 시 NULL (C12)',
    visit_id             BIGINT       NULL COMMENT '완료 시',
    created_at           DATETIME     NOT NULL COMMENT '귀속 계산 기준 시각 (FR-VIS-04, E3)',
    PRIMARY KEY (id),
    -- 겹침 검사 · 캘린더 기간 조회 (FR-RSV-02·04, NFR-01)
    KEY idx_reservation_staff_start (shop_id, staff_id, start_at),
    KEY idx_reservation_shop_start (shop_id, start_at),
    CONSTRAINT fk_reservation_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_reservation_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT fk_reservation_staff FOREIGN KEY (staff_id) REFERENCES staff (id),
    CONSTRAINT fk_reservation_visit FOREIGN KEY (visit_id) REFERENCES visit (id)
);

-- 예약의 예정 시술. 예약당 1개 이상은 서버에서 검증한다 (A13)
CREATE TABLE reservation_service (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    shop_id               BIGINT NOT NULL,
    reservation_id        BIGINT NOT NULL,
    service_menu_id       BIGINT NOT NULL,
    attributed_message_id BIGINT NULL COMMENT '귀속 발송 campaign_message.id (F15). FK는 V5',
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_service_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_reservation_service_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT fk_reservation_service_menu FOREIGN KEY (service_menu_id) REFERENCES service_menu (id)
);

-- 결제는 예약에서만 등록한다 (FR-VIS-02). 한 방문에 여러 건 가능 (C6 · A13)
CREATE TABLE payment (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id        BIGINT      NOT NULL,
    customer_id    BIGINT      NULL COMMENT '고객 없음으로 마감하면 비움 (E3)',
    reservation_id BIGINT      NOT NULL,
    visit_id       BIGINT      NULL,
    amount         INT         NOT NULL COMMENT '원 단위 정수',
    method         VARCHAR(20) NOT NULL COMMENT 'CARD | CASH | TRANSFER | ETC',
    paid_at        DATETIME    NOT NULL,
    canceled       BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY idx_payment_shop_paid (shop_id, paid_at),
    CONSTRAINT fk_payment_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_payment_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT fk_payment_visit FOREIGN KEY (visit_id) REFERENCES visit (id)
);

-- 방문의 실제 시술. 행 수 = 그 방문의 시술 건수. 담당 스탭과 귀속은 시술마다 기록 (A13)
CREATE TABLE visit_service (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id               BIGINT      NOT NULL,
    visit_id              BIGINT      NOT NULL,
    reservation_id        BIGINT      NULL COMMENT '이 시술이 온 예약. 가져온 방문이면 비움',
    service_menu_id       BIGINT      NULL COMMENT '가져온 방문이면 비움 (메뉴 미상)',
    category              VARCHAR(20) NOT NULL COMMENT 'NAIL | LASH | WAXING | SEMI_PERMANENT | ETC',
    staff_id              BIGINT      NULL COMMENT '담당 스탭. 가져온 방문이면 비움',
    cycle_excluded        BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '주기 계산 제외 (FR-VIS-05)',
    attributed_message_id BIGINT      NULL COMMENT '귀속 발송 campaign_message.id (F15). FK는 V5',
    PRIMARY KEY (id),
    CONSTRAINT fk_visit_service_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_visit_service_visit FOREIGN KEY (visit_id) REFERENCES visit (id),
    CONSTRAINT fk_visit_service_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT fk_visit_service_menu FOREIGN KEY (service_menu_id) REFERENCES service_menu (id),
    CONSTRAINT fk_visit_service_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
);

-- 파일은 서버의 비공개 디렉터리에 두고 키만 저장한다 (F16)
CREATE TABLE photo (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT       NOT NULL,
    visit_id    BIGINT       NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    uploaded_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_photo_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_photo_visit FOREIGN KEY (visit_id) REFERENCES visit (id)
);

-- 방문 메모. 방문 하나에 여러 개 (FR-PAY-04, FR-CUS-07, F1)
CREATE TABLE visit_note (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    shop_id    BIGINT   NOT NULL,
    visit_id   BIGINT   NOT NULL,
    staff_id   BIGINT   NOT NULL COMMENT '작성 스탭',
    content    TEXT     NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_visit_note_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_visit_note_visit FOREIGN KEY (visit_id) REFERENCES visit (id),
    CONSTRAINT fk_visit_note_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
);

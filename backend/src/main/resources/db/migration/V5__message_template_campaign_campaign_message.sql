-- V5 메시지 템플릿 · 캠페인 · 캠페인 메시지 (Keepsy #10)
-- 기준: 요구사항명세서 5장. enum 값은 docs/CONTRACTS.md 3장, 문자열로 저장한다.
-- V3 reservation_service · visit_service의 귀속 발송 ID 외래 키(→ campaign_message.id, F15)를 이 파일 끝에서 더한다.
-- 메시지는 가상 발송기로만 처리한다. 실제 문자는 보내지 않는다 (A6).

-- 메시지 종류(광고/안내)는 용도로 정해지므로 저장하지 않는다: CONSENT_RECONFIRM만 안내, 나머지는 광고 (FR-CMP-03)
CREATE TABLE message_template (
    id      BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id BIGINT       NOT NULL,
    name    VARCHAR(100) NOT NULL,
    body    TEXT         NOT NULL COMMENT '변수 {고객명} {최근시술} {경과일} {매장명} 등 치환 전 본문 (C10)',
    purpose VARCHAR(20)  NOT NULL COMMENT 'REVISIT | CHECK_IN | WIN_BACK | CONSENT_RECONFIRM | ETC',
    PRIMARY KEY (id),
    -- 다음 액션 제안이 용도로 템플릿을 고른다 (FR-PRD-06)
    KEY idx_message_template_shop_purpose (shop_id, purpose),
    CONSTRAINT fk_message_template_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
);

CREATE TABLE campaign (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id               BIGINT       NOT NULL,
    name                  VARCHAR(100) NOT NULL,
    category              VARCHAR(20)  NULL COMMENT 'NAIL | LASH | WAXING | SEMI_PERMANENT | ETC. 동의 재확인 안내면 비움 (E7)',
    template_id           BIGINT       NOT NULL,
    message_kind          VARCHAR(20)  NOT NULL COMMENT 'AD | INFO. 템플릿 용도로 정해짐 (FR-CMP-03)',
    control_percent       INT          NOT NULL DEFAULT 0 COMMENT '대조군 비율 % (FR-CMP-18, E2)',
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | SCHEDULED | SENDING | SENT | CANCELED (F7)',
    reviewed_by_staff_id  BIGINT       NULL COMMENT '검토 체크한 스탭 (FR-CMP-06)',
    reviewed_at           DATETIME     NULL,
    scheduled_at          DATETIME     NULL COMMENT '예약 발송 시각 (FR-CMP-09)',
    sent_at               DATETIME     NULL COMMENT '발송 시작 시각',
    cancel_reason         VARCHAR(30)  NULL COMMENT 'USER_CANCELED | DAILY_LIMIT_EXCEEDED (F7 보완)',
    canceled_at           DATETIME     NULL,
    PRIMARY KEY (id),
    -- 스케줄러가 시각이 된 예약 발송을 찾는다 (FR-CMP-09, ARCHITECTURE 7장)
    KEY idx_campaign_status_scheduled (status, scheduled_at),
    -- 캠페인 목록 · 최근 캠페인 (FR-CMP-10, FR-DSH-04)
    KEY idx_campaign_shop_sent (shop_id, sent_at),
    CONSTRAINT fk_campaign_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    -- 쓰인 템플릿은 지울 수 없다. 템플릿 삭제(#86)는 이 제약을 지킨다
    CONSTRAINT fk_campaign_template FOREIGN KEY (template_id) REFERENCES message_template (id),
    CONSTRAINT fk_campaign_reviewer FOREIGN KEY (reviewed_by_staff_id) REFERENCES staff (id)
);

-- 고객별 발송 기록. 대조군 행은 발송하지 않고 비교용으로만 남긴다 (FR-CMP-18).
-- 귀속(FR-ANL-03) · 재발송 제한(FR-CMP-02)에는 발송 성공한 광고만 쓰고, 그 시각은 result_at이다.
-- 가상 휴대폰 수신함은 종류와 상관없이 수신 번호로 조회한다 (FR-CMP-16).
CREATE TABLE campaign_message (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id          BIGINT       NOT NULL,
    campaign_id      BIGINT       NULL COMMENT '수신 거부 처리 안내면 비움 (FR-CMP-11)',
    kind             VARCHAR(20)  NOT NULL COMMENT 'AD | UNSUBSCRIBE_NOTICE | CONSENT_RECONFIRM',
    customer_id      BIGINT       NOT NULL,
    category         VARCHAR(20)  NULL COMMENT '캠페인 카테고리. 동의 재확인 · 수신 거부 안내면 비움 (E7)',
    control_group    BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '대조군 여부 (FR-CMP-18)',
    recipient_phone  VARCHAR(20)  NULL COMMENT '수신 번호. 고객 삭제 시 NULL (C12)',
    message_type     VARCHAR(10)  NULL COMMENT 'SMS | LMS (FR-CMP-05). 대조군 행은 비움',
    body             TEXT         NULL COMMENT '치환된 본문. 고객 삭제 시 NULL (C12)',
    send_status      VARCHAR(20)  NULL COMMENT 'PENDING | SENT | FAILED. 대조군 행은 비움 (FR-CMP-10)',
    failure_reason   VARCHAR(200) NULL,
    result_at        DATETIME     NULL COMMENT '발송 결과 시각. SENT면 발송 성공 시각',
    PRIMARY KEY (id),
    -- 같은 캠페인 중복 발송의 두 번째 방어선 (F7). 수신 거부 안내(campaign_id NULL)는 MySQL 고유 인덱스에서 빠진다
    UNIQUE KEY uk_campaign_message_campaign_customer (campaign_id, customer_id),
    KEY idx_campaign_message_shop_phone (shop_id, recipient_phone),
    -- 스케줄러가 PENDING을 이어서 처리한다 (F7 복구, FR-CMP-10)
    KEY idx_campaign_message_status (send_status),
    -- 고객별 귀속 · 재발송 제한 · 수신 이력 (FR-ANL-03, FR-CMP-02, FR-ANL-04)
    KEY idx_campaign_message_customer_result (customer_id, result_at),
    CONSTRAINT fk_campaign_message_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_campaign_message_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    CONSTRAINT fk_campaign_message_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

-- V3에서 미뤄 둔 귀속 발송 ID 외래 키 (F15)
ALTER TABLE reservation_service
    ADD CONSTRAINT fk_reservation_service_attributed_message
        FOREIGN KEY (attributed_message_id) REFERENCES campaign_message (id);

ALTER TABLE visit_service
    ADD CONSTRAINT fk_visit_service_attributed_message
        FOREIGN KEY (attributed_message_id) REFERENCES campaign_message (id);

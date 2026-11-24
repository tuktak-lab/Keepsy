-- V7 캠페인 대상 (Keepsy #89, 결정 F41)
-- 캠페인을 만들 때 고른 대상과 대조군. 발송을 시작할 때 이 표에서 고객별 campaign_message를 만든다 (F7).
CREATE TABLE campaign_target (
    shop_id       BIGINT  NOT NULL,
    campaign_id   BIGINT  NOT NULL,
    customer_id   BIGINT  NOT NULL,
    control_group BOOLEAN NOT NULL COMMENT '대조군 여부 (FR-CMP-18). 발송하지 않고 발송 때 대조군 행으로 기록',
    -- 한 캠페인에서 고객당 한 행 (FR-CMP-01)
    PRIMARY KEY (campaign_id, customer_id),
    CONSTRAINT fk_campaign_target_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_campaign_target_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    CONSTRAINT fk_campaign_target_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

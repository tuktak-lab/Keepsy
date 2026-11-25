-- V10 비밀번호 재설정 토큰 · 가상 메일함 (Keepsy #25, FR-ACC-06 P2, 결정 F14 · F49, 명세서 5장)
-- V9(campaign_message.send_after, #99)가 먼저 들어간 뒤에 적용한다.

-- 재설정 토큰은 SHA-256 해시로만 저장하고 한 번만 쓴다 (F14). 발급 후 30분 (C3)
CREATE TABLE password_reset_token (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT      NOT NULL,
    token_hash  CHAR(64)    NOT NULL COMMENT 'SHA-256 hex. 토큰 원문은 저장하지 않는다',
    expires_at  DATETIME    NOT NULL COMMENT '발급 후 30분 (C3)',
    used_at     DATETIME    NULL COMMENT '사용 · 무효 처리 시각. 비어 있을 때만 쓸 수 있다',
    PRIMARY KEY (id),
    UNIQUE KEY uk_password_reset_token_hash (token_hash),
    KEY idx_password_reset_token_shop (shop_id, used_at),
    CONSTRAINT fk_password_reset_token_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
);

-- 가상 메일함. 메일 발송 수단이 정해질 때까지 개발용 발송기가 쌓고 실제 메일은 나가지 않는다 (F49)
CREATE TABLE virtual_mail (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id         BIGINT       NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    subject         VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    sent_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_virtual_mail_shop_recipient (shop_id, recipient_email, sent_at),
    CONSTRAINT fk_virtual_mail_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
);

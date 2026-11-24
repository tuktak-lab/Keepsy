-- V8 동의 정정 대상 (Keepsy #49, 결정 F44)
-- 정정은 원래 행을 고치지 않고 새 행으로 쌓고, 새 행에 고친 이력 행을 남긴다. 정정 행의 recorded_at은 처리 시각이 아니라 효력 일시다.
ALTER TABLE consent
    ADD COLUMN corrects_consent_id BIGINT NULL COMMENT '정정 행이면 고친 이력 행 (F44)',
    ADD CONSTRAINT fk_consent_corrects FOREIGN KEY (corrects_consent_id) REFERENCES consent (id);

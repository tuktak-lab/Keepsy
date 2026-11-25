-- V9 발송 예정 시각 (Keepsy #99, 결정 F48, 명세서 5장 campaign_message)
-- 야간(서울 21:00~08:00)에 접수된 수신 거부 처리 안내를 다음 08:00에 보낸다 (FR-CMP-07 · 11).
-- 발송기는 PENDING 중 send_after가 비었거나 지난 행만 보낸다. 기존 행은 비어 있어 그대로 바로 보낸다.
ALTER TABLE campaign_message
    ADD COLUMN send_after DATETIME NULL COMMENT '이 시각 이후 발송. 비우면 바로 (F48)' AFTER result_at;

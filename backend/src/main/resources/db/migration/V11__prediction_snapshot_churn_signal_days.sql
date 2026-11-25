-- V11 예측 스냅샷의 이탈 신호 기간 (Keepsy #104, 결정 F57)
-- 스냅샷을 만들거나 고쳐 쓸 때 그때의 이탈 신호 기간(평균 간격과 매장 상한 중 작은 값, 최소 1)을 남긴다.
-- 대조군 비교의 주기 내 재방문율이 "발송 시각 예측의 이탈 신호 기간"을 쓴다 (FR-ANL-09). 이 칸이 생기기 전 행은 NULL.
ALTER TABLE prediction_snapshot
    ADD COLUMN churn_signal_days INT NULL COMMENT '이탈 신호 기간(일). NULL이면 F57 이전 스냅샷' AFTER basis_visit_count;

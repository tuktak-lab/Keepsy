package com.tuktaklab.keepsy.prediction;

/**
 * 예측 상태 (FR-PRD-04, 기획서 9장 「상태 분류」). 값 이름은 CONTRACTS 3장 [제안]을 따른다. 저장하지 않고 조회할 때 오늘 날짜로 계산한다 (A12).
 */
public enum PredictionStatus {
    /** 예정: 오늘 &lt; 예측 시작일 */
    SCHEDULED,
    /** 주기 도래: 예측 시작일 ≤ 오늘 ≤ 예측 종료일 */
    DUE,
    /** 이탈 신호: 예측 종료일 &lt; 오늘 ≤ 예측 종료일 + 이탈 신호 기간 */
    CHURN_SIGNAL,
    /** 장기 미방문: 이탈 신호 기간 이후 */
    LONG_ABSENT,
    /** 예측 없음: 해당 카테고리 방문이 모두 주기 계산 제외 (FR-PRD-01) */
    NO_PREDICTION
}

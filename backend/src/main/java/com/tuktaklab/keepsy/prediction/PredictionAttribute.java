package com.tuktaklab.keepsy.prediction;

/** 시술 메뉴의 예측 속성 (FR-SET-02, E5). 값 이름은 CONTRACTS 3장 [제안]을 따른다. */
public enum PredictionAttribute {
    /** 일반: 다음 방문 주기를 정하는 시술 */
    NORMAL,
    /** 주기 계산 제외: 리터치·보수·애드온처럼 다음 방문 시점을 정하지 않는 시술 */
    EXCLUDE,
    /** 종료 신호: 젤 제거만처럼 그만두는 손님이 받는 시술 */
    END_SIGNAL
}

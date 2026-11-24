package com.tuktaklab.keepsy.settings.port;

/** 메뉴 설정이 바뀌어 다시 계산할 예측 (다른 도메인의 일, DEVELOPMENT 4장 「도메인 연동」) */
public interface PredictionRecalculation {

    /** 메뉴의 기본 주기나 예측 속성이 바뀌었을 때. 부르는 쪽 트랜잭션 안에서 함께 커밋된다 (FR-SET-02) */
    void menuChanged(long shopId, long menuId);
}

package com.tuktaklab.keepsy.prediction;

/** 처리 표시 (FR-PRD-07, CONTRACTS 3장). 관리 종료는 인사이트 · 오늘 관리할 고객에서 숨긴다 (F55) */
public enum HandlingMark {
    CONTACTED,
    ON_HOLD,
    CLOSED
}

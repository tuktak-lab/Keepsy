package com.tuktaklab.keepsy.prediction;

/**
 * 기본 주기 범위(일). 메뉴별 기본 주기와 카테고리 기본 주기에 쓴다 (FR-SET-02).
 */
public record CycleRange(int minDays, int maxDays) {

    public CycleRange {
        if (minDays <= 0 || minDays > maxDays) {
            throw new IllegalArgumentException("기본 주기는 0보다 크고 최소 ≤ 최대여야 한다: " + minDays + "~" + maxDays);
        }
    }
}

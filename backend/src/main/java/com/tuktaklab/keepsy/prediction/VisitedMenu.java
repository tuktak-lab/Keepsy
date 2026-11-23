package com.tuktaklab.keepsy.prediction;

import java.util.Objects;

/**
 * 한 방문에서 해당 카테고리로 받은 시술 하나.
 *
 * @param attribute 메뉴의 예측 속성
 * @param excludedThisVisit 방문별 [이번 시술 주기 계산 제외] 체크 (FR-VIS-05)
 * @param cycle 메뉴의 기본 주기. 메뉴 없이 카테고리만 있는 가져온 방문(FR-CUS-13)이면 {@code null}
 */
public record VisitedMenu(
        PredictionAttribute attribute, boolean excludedThisVisit, CycleRange cycle) {

    public VisitedMenu {
        Objects.requireNonNull(attribute, "attribute");
    }

    /** 메뉴 없이 카테고리만 기록된 가져온 방문의 시술. 일반 시술로 보고 카테고리 기본 주기를 쓴다 (A13, E5). */
    public static VisitedMenu imported() {
        return new VisitedMenu(PredictionAttribute.NORMAL, false, null);
    }

    boolean countsForCycle() {
        return attribute != PredictionAttribute.EXCLUDE && !excludedThisVisit;
    }
}

package com.tuktaklab.keepsy.prediction;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 고객 한 명의 한 시술 카테고리 방문 하나 (같은 날 방문은 1건으로 합쳐진 상태, C6).
 * 고객 없는 방문은 예측에 쓰지 않으므로 넘기지 않는다 (E3).
 */
public record CategoryVisit(LocalDate date, List<VisitedMenu> menus) {

    public CategoryVisit {
        Objects.requireNonNull(date, "date");
        if (menus == null || menus.isEmpty()) {
            throw new IllegalArgumentException("방문에는 해당 카테고리 시술이 1개 이상 있어야 한다");
        }
        menus = List.copyOf(menus);
    }
}

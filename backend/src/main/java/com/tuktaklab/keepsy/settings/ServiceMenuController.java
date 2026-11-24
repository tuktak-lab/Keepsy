package com.tuktaklab.keepsy.settings;

import com.tuktaklab.keepsy.common.security.RequireWorker;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 시술 메뉴 (CONTRACTS 4 · 6장 SET [제안]) */
@RestController
@RequiredArgsConstructor
class ServiceMenuController {

    private final ServiceMenuService menuService;
    private final ShopSession shopSession;

    record MenuItem(
            long id,
            String category,
            String name,
            int price,
            int durationMinutes,
            int cycleMinDays,
            int cycleMaxDays,
            String predictionAttribute) {}

    /** 시술 메뉴 (FR-SET-02). 근무자. 예약 화면이 소요 시간 합계를 기본값으로 쓴다 (FR-RSV-01) */
    @RequireWorker
    @GetMapping("/api/service-menus")
    List<MenuItem> menus() {
        return menuService.menus(shopSession.shopId()).stream()
                .map(m ->
                        new MenuItem(
                                m.getId(),
                                m.getCategory(),
                                m.getName(),
                                m.getPrice(),
                                m.getDurationMinutes(),
                                m.getCycleMinDays(),
                                m.getCycleMaxDays(),
                                m.getPredictionAttribute()))
                .toList();
    }
}

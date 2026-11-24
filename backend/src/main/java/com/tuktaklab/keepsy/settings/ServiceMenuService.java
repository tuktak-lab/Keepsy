package com.tuktaklab.keepsy.settings;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.security.ShopScope;
import com.tuktaklab.keepsy.settings.port.PredictionRecalculation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 시술 메뉴 (FR-SET-02). 매장이 직접 등록하는 작은 목록이라 페이징하지 않는다 (F22) */
@Service
@RequiredArgsConstructor
public class ServiceMenuService {

    private final ServiceMenuRepository menuRepository;
    private final PredictionRecalculation predictions;

    public record NewMenu(
            String category,
            String name,
            int price,
            int durationMinutes,
            int cycleMinDays,
            int cycleMaxDays,
            String predictionAttribute) {}

    /** null인 칸은 바꾸지 않는다 (F31) */
    public record MenuChanges(
            String name,
            Integer price,
            Integer durationMinutes,
            Integer cycleMinDays,
            Integer cycleMaxDays,
            String predictionAttribute) {}

    @Transactional(readOnly = true)
    public List<ServiceMenu> menus(long shopId) {
        return menuRepository.findAllInDisplayOrder(shopId);
    }

    /**
     * 새 메뉴는 받은 고객이 없어 예측을 다시 계산하지 않는다.
     *
     * @return 만든 메뉴 ID
     * @throws ApiException 30분 단위가 아니거나 최소 주기가 최대보다 크면 {@code invalid}
     */
    @Transactional
    public long create(long shopId, NewMenu in) {
        ServiceMenu menu =
                ServiceMenu.create(
                        shopId,
                        in.category(),
                        in.name(),
                        in.price(),
                        in.durationMinutes(),
                        in.cycleMinDays(),
                        in.cycleMaxDays(),
                        in.predictionAttribute());
        return menuRepository.save(menu).getId();
    }

    /**
     * 보낸 칸만 바꾸고, 기본 주기나 예측 속성이 바뀌면 그 메뉴를 받은 고객 전체의 예측을 같은 트랜잭션에서 다시 계산한다 (FR-SET-02, FR-VIS-05).
     *
     * @throws ApiException 다른 매장 · 없는 메뉴면 {@code not_found}, 규칙을 어기면 {@code invalid}
     */
    @Transactional
    public void update(long shopId, long menuId, MenuChanges c) {
        ServiceMenu menu = ShopScope.orNotFound(menuRepository.findByIdAndShopId(menuId, shopId));
        boolean predictionChanged =
                menu.change(
                        c.name(),
                        c.price(),
                        c.durationMinutes(),
                        c.cycleMinDays(),
                        c.cycleMaxDays(),
                        c.predictionAttribute());
        if (predictionChanged) {
            // 예측 재계산은 메뉴 주기 · 속성을 SQL로 읽으므로 먼저 반영한다
            menuRepository.flush();
            predictions.menuChanged(shopId, menuId);
        }
    }
}

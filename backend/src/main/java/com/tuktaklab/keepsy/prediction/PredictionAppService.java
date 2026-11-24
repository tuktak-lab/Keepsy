package com.tuktaklab.keepsy.prediction;

import com.tuktaklab.keepsy.prediction.port.PredictionVisits;
import com.tuktaklab.keepsy.prediction.port.PredictionVisits.VisitedService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다음 방문 기간 저장 · 재계산 (FR-PRD-01, FR-SET-02). 계산 규칙은 {@link NextVisitCalculator}, 이 클래스는 방문을 모아 넘기고
 * 결과를 {@code customer_prediction}에 갱신(upsert)한다. 야간 배치는 없다 (A12).
 *
 * <p>다른 도메인이 부르는 곳: 방문 생성 · 수정 · 삭제 → {@link #recalculate(long, long)}, 메뉴 기본 주기 · 예측 속성 변경 →
 * {@link #recalculateForMenu}, 카테고리 기본 주기 변경 → {@link #recalculateForCategoryDefault}. 부르는 쪽 트랜잭션
 * 안에서 함께 커밋된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionAppService {

    private final PredictionVisits visits;
    private final CustomerPredictionRepository predictions;

    /** 방문이 생기거나 수정 · 삭제된 고객 한 명 */
    @Transactional
    public void recalculate(long shopId, long customerId) {
        recalculate(shopId, List.of(customerId));
    }

    /** 이 메뉴를 받은 고객 전체 */
    @Transactional
    public void recalculateForMenu(long shopId, long menuId) {
        recalculate(shopId, visits.customersWithMenu(shopId, menuId));
    }

    /** 이 카테고리에 메뉴 없는 가져온 방문이 있는 고객 전체 */
    @Transactional
    public void recalculateForCategoryDefault(long shopId, String category) {
        recalculate(shopId, visits.customersWithMenulessVisits(shopId, category));
    }

    /**
     * 고객들의 카테고리별 예측을 한 번에 다시 계산한다. 방문과 기존 예측을 각각 한 번에 읽고 한 번에 저장한다 (반복문 안 조회 없음, DEVELOPMENT 4장).
     * 카테고리 방문이 하나도 남지 않으면(방문 삭제) 그 행을 지운다.
     */
    @Transactional
    public void recalculate(long shopId, Collection<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return;
        }
        Map<String, CycleRange> defaults = visits.categoryDefaults(shopId);
        // 고객 → 카테고리 → 방문일 → 그날 받은 시술 (같은 고객 · 같은 날은 방문 하나, C6)
        Map<Long, Map<String, Map<LocalDate, List<VisitedMenu>>>> byCustomer = new HashMap<>();
        for (VisitedService s : visits.visitedServices(shopId, customerIds)) {
            byCustomer
                    .computeIfAbsent(s.customerId(), k -> new HashMap<>())
                    .computeIfAbsent(s.category(), k -> new TreeMap<>())
                    .computeIfAbsent(s.date(), k -> new ArrayList<>())
                    .add(
                            s.attribute() == null
                                    ? VisitedMenu.imported()
                                    : new VisitedMenu(
                                            s.attribute(), s.excludedThisVisit(), s.cycle()));
        }
        Map<String, CustomerPrediction> existing =
                predictions.findByShopIdAndCustomerIdIn(shopId, customerIds).stream()
                        .collect(
                                Collectors.toMap(
                                        p -> p.getCustomerId() + ":" + p.getCategory(), p -> p));

        List<CustomerPrediction> save = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (long customerId : Set.copyOf(customerIds)) {
            for (var cat : byCustomer.getOrDefault(customerId, Map.of()).entrySet()) {
                List<CategoryVisit> catVisits =
                        cat.getValue().entrySet().stream()
                                .map(d -> new CategoryVisit(d.getKey(), d.getValue()))
                                .toList();
                String key = customerId + ":" + cat.getKey();
                CustomerPrediction row =
                        existing.getOrDefault(
                                key, CustomerPrediction.of(shopId, customerId, cat.getKey()));
                row.apply(NextVisitCalculator.calculate(catVisits, defaults.get(cat.getKey())));
                save.add(row);
                kept.add(key);
            }
        }
        List<CustomerPrediction> remove =
                existing.entrySet().stream()
                        .filter(e -> !kept.contains(e.getKey()))
                        .map(Map.Entry::getValue)
                        .toList();
        predictions.saveAll(save);
        predictions.deleteAll(remove);
        log.debug(
                "예측 재계산 shopId={} customers={} saved={} removed={}",
                shopId,
                customerIds.size(),
                save.size(),
                remove.size());
    }
}

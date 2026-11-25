package com.tuktaklab.keepsy.prediction;

import com.tuktaklab.keepsy.prediction.port.PredictionVisits;
import com.tuktaklab.keepsy.prediction.port.PredictionVisits.VisitedService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
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
    private final PredictionSnapshotRepository snapshots;
    private final Clock clock;

    /** 방문이 생기거나 수정 · 삭제된 고객 한 명 */
    @Transactional
    public void recalculate(long shopId, long customerId) {
        recalculate(shopId, List.of(customerId));
    }

    /**
     * 고객 삭제 (FR-CUS-10, C12): 현재 예측을 지운다. 예측 스냅샷은 정확도 통계용으로 남긴다. 삭제된 고객은 재계산이 방문을 읽지 않아 다시 생기지 않는다.
     */
    @Transactional
    public void deleteForCustomer(long shopId, long customerId) {
        predictions.deleteCustomer(shopId, customerId);
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
        // 아직 평가 전인 최신 스냅샷 (고객 × 카테고리, F23)
        Map<String, PredictionSnapshot> open =
                snapshots.findByShopIdAndCustomerIdInAndHitIsNull(shopId, customerIds).stream()
                        .collect(
                                Collectors.toMap(
                                        s -> s.getCustomerId() + ":" + s.getCategory(),
                                        s -> s,
                                        (a, b) -> a.getId() > b.getId() ? a : b));
        LocalDateTime now = LocalDateTime.now(clock);
        int churnCapDays = visits.churnCapDays(shopId);
        List<PredictionSnapshot> snapshotSave = new ArrayList<>();
        List<Rollback> rollbacks = new ArrayList<>();

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
                Optional<NextVisitPrediction> result =
                        NextVisitCalculator.calculate(catVisits, defaults.get(cat.getKey()));
                Supplier<PredictionSnapshot> create =
                        () ->
                                PredictionSnapshot.of(
                                        shopId,
                                        customerId,
                                        cat.getKey(),
                                        result.get(),
                                        churnCapDays,
                                        now);
                LocalDate newBasis = result.map(NextVisitPrediction::lastVisitDate).orElse(null);
                if (movedBack(row.getLastServiceDate(), newBasis)) {
                    boolean predicts = result.isPresent() && !result.get().endSignal();
                    rollbacks.add(
                            new Rollback(
                                    customerId,
                                    key,
                                    open.get(key),
                                    catVisits.stream()
                                            .filter(NextVisitCalculator::counts)
                                            .map(CategoryVisit::date)
                                            .collect(Collectors.toSet()),
                                    predicts ? create : null));
                } else {
                    snapshot(
                                    open.get(key),
                                    row.getLastServiceDate(),
                                    result,
                                    catVisits,
                                    create,
                                    churnCapDays,
                                    now)
                            .forEach(snapshotSave::add);
                }
                row.apply(result);
                save.add(row);
                kept.add(key);
            }
        }
        List<CustomerPrediction> remove =
                existing.entrySet().stream()
                        .filter(e -> !kept.contains(e.getKey()))
                        .map(Map.Entry::getValue)
                        .toList();
        // 카테고리 방문이 하나도 남지 않음 = 기준 방문일이 없어짐 (F62)
        remove.stream()
                .filter(p -> p.getLastServiceDate() != null)
                .forEach(p -> {
                    String key = p.getCustomerId() + ":" + p.getCategory();
                    rollbacks.add(
                            new Rollback(p.getCustomerId(), key, open.get(key), Set.of(), null));
                });
        List<PredictionSnapshot> snapshotDelete = rollBack(shopId, rollbacks, snapshotSave);
        predictions.saveAll(save);
        predictions.deleteAll(remove);
        snapshots.deleteAll(snapshotDelete);
        snapshots.saveAll(snapshotSave);
        predictions.releaseHandling(shopId, customerIds);
        log.debug(
                "예측 재계산 shopId={} customers={} saved={} removed={}",
                shopId,
                customerIds.size(),
                save.size(),
                remove.size());
    }

    /**
     * 기준 방문일이 앞당겨진 고객 × 카테고리 (F62).
     *
     * @param counted 남은 유효 방문일 (주기 계산 제외 방문은 뺌)
     * @param create 새 기준으로 예측하면 스냅샷 만들기, 예측 없음 · 종료 신호면 null
     */
    private record Rollback(
            long customerId,
            String key,
            PredictionSnapshot open,
            Set<LocalDate> counted,
            Supplier<PredictionSnapshot> create) {}

    /** 방문 삭제 · 수정으로 기준 방문일이 앞당겨지거나 없어졌는지 (F62) */
    private static boolean movedBack(LocalDate oldBasis, LocalDate newBasis) {
        return oldBasis != null && (newBasis == null || newBasis.isBefore(oldBasis));
    }

    /**
     * 기준 방문일이 앞당겨지면 (F62, F23 · F8 예외) 평가 전 최신 스냅샷은 고쳐 쓰지 않고 지우고, 남은 유효 방문이 아닌 날로 평가된 스냅샷(지운 방문으로
     * 평가)은 평가를 되돌려 다시 평가 전으로 만든다. 그 밖의 평가된 스냅샷은 그대로 둔다. 되돌린 스냅샷이 새 기준의 예측이므로, 되돌린 것이 없을 때만 새로 만든다.
     *
     * @param save 되돌리거나 만든 스냅샷을 더한다
     * @return 지울 스냅샷
     */
    private List<PredictionSnapshot> rollBack(
            long shopId, List<Rollback> rollbacks, List<PredictionSnapshot> save) {
        if (rollbacks.isEmpty()) {
            return List.of();
        }
        Map<String, List<PredictionSnapshot>> evaluated =
                snapshots
                        .findByShopIdAndCustomerIdInAndHitIsNotNull(
                                shopId,
                                rollbacks.stream().map(Rollback::customerId).distinct().toList())
                        .stream()
                        .collect(
                                Collectors.groupingBy(
                                        s -> s.getCustomerId() + ":" + s.getCategory()));
        List<PredictionSnapshot> delete = new ArrayList<>();
        for (Rollback r : rollbacks) {
            if (r.open() != null) {
                delete.add(r.open());
            }
            boolean reverted = false;
            for (PredictionSnapshot s : evaluated.getOrDefault(r.key(), List.of())) {
                if (!r.counted().contains(s.getActualVisitDate())) {
                    s.revertEvaluation();
                    save.add(s);
                    reverted = true;
                }
            }
            if (!reverted && r.create() != null) {
                save.add(r.create().get());
            }
        }
        return delete;
    }

    /**
     * 예측 스냅샷 (FR-PRD-08, F23). 기준 마지막 방문일이 뒤로 바뀌면 이전 기준일 뒤의 첫 유효 방문으로 평가 전 스냅샷을 평가하고 새로 만든다. 기준이
     * 같으면 평가 전 스냅샷을 고쳐 쓴다(없으면 만든다). 앞당겨지면 {@link #rollBack}. 종료 신호 · 예측 없음은 새로 만들지 않는다.
     *
     * @param oldBasis 재계산 전 기준 마지막 방문일 (처음이거나 예측 없음이었으면 null)
     * @return 저장할 스냅샷 (0~2개)
     */
    private static List<PredictionSnapshot> snapshot(
            PredictionSnapshot open,
            LocalDate oldBasis,
            Optional<NextVisitPrediction> result,
            List<CategoryVisit> visits,
            Supplier<PredictionSnapshot> create,
            int churnCapDays,
            LocalDateTime now) {
        LocalDate newBasis = result.map(NextVisitPrediction::lastVisitDate).orElse(null);
        boolean predicts = result.isPresent() && !result.get().endSignal();
        List<PredictionSnapshot> out = new ArrayList<>();
        if (oldBasis != null && newBasis != null && newBasis.isAfter(oldBasis)) {
            if (open != null) {
                // 다음 방문 = 이전 기준일 뒤의 첫 유효 방문 (주기 계산 제외 방문은 빼고, FR-PRD-01과 같은 기준)
                visits.stream()
                        .filter(NextVisitCalculator::counts)
                        .map(CategoryVisit::date)
                        .filter(d -> d.isAfter(oldBasis))
                        .min(LocalDate::compareTo)
                        .ifPresent(open::evaluate);
                out.add(open);
            }
            if (predicts) {
                out.add(create.get());
            }
        } else if (predicts) {
            if (open != null) {
                open.rewrite(result.get(), churnCapDays, now);
                out.add(open);
            } else {
                out.add(create.get());
            }
        }
        return out;
    }
}

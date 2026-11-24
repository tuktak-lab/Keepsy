package com.tuktaklab.keepsy.prediction.port;

import com.tuktaklab.keepsy.prediction.CycleRange;
import com.tuktaklab.keepsy.prediction.PredictionAttribute;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 예측 계산에 필요한 방문 · 메뉴 · 매장 기본 주기 (다른 도메인의 값, DEVELOPMENT 4장 「도메인 연동」). 이름과 반환 타입은 예측 도메인이 필요한 모양이다.
 */
public interface PredictionVisits {

    /**
     * 방문 하나에서 받은 시술 하나.
     *
     * @param attribute 메뉴 예측 속성. 메뉴 없는 가져온 방문이면 null
     * @param cycle 메뉴 기본 주기. 메뉴 없는 가져온 방문이면 null
     */
    record VisitedService(
            long customerId,
            LocalDate date,
            String category,
            PredictionAttribute attribute,
            boolean excludedThisVisit,
            CycleRange cycle) {}

    /** 고객들의 방문 시술. 삭제된 고객과 고객 없는 방문은 빼고 준다 (FR-CUS-10, E3) */
    List<VisitedService> visitedServices(long shopId, Collection<Long> customerIds);

    /** 카테고리(NAIL · LASH · ...) → 매장의 카테고리 기본 주기 (FR-SET-02, C16) */
    Map<String, CycleRange> categoryDefaults(long shopId);

    /** 이 메뉴를 받은 적 있는 고객 (메뉴 기본 주기 · 예측 속성 변경 시 재계산 대상, FR-SET-02) */
    List<Long> customersWithMenu(long shopId, long menuId);

    /** 이 카테고리에 메뉴 없는 가져온 방문이 있는 고객 (카테고리 기본 주기 변경 시 재계산 대상) */
    List<Long> customersWithMenulessVisits(long shopId, String category);

    /** 세션 매장의 삭제되지 않은 고객인지 (NFR-03, FR-CUS-10) */
    boolean isActiveCustomer(long shopId, long customerId);

    /** 이탈 신호 기간 상한 (매장 설정, 기본 120일, FR-SET-03) */
    int churnCapDays(long shopId);

    /** 다음 예약(오늘 이후의 확정 예약, 아직 완료되지 않은 오늘 예약 포함)의 예정 시술 카테고리 (FR-PRD-04, A11 · A13) */
    Set<String> upcomingCategories(long shopId, long customerId, LocalDate today);
}

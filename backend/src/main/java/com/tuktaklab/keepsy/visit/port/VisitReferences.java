package com.tuktaklab.keepsy.visit.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 방문이 다른 도메인(예약 · 시술 메뉴 · 고객)에서 필요한 값과 동작 (DEVELOPMENT 4장 「도메인 연동」). 모두 세션 매장 조건 (NFR-03) */
public interface VisitReferences {

    /** 완료 · 마감할 예약을 행 잠금으로 읽는다. 없거나 다른 매장 것이면 not_found */
    ReservationToClose lockReservation(long shopId, long reservationId);

    /** 예약을 완료로 바꾸고 방문을 연결한다 */
    void completeReservation(
            long shopId, long reservationId, long visitId, boolean confirmServices);

    /** 그 매장 메뉴의 시술 카테고리. 다른 매장 · 없는 메뉴는 결과에서 빠진다 */
    Map<Long, String> menuCategories(long shopId, Collection<Long> serviceMenuIds);

    /** 고객 행을 트랜잭션 끝까지 잠근다. 같은 고객 · 같은 날 방문이 동시에 두 건 생기지 않게 한다 (C6) */
    void lockCustomer(long shopId, long customerId);

    record ReservationToClose(
            long id,
            Long customerId,
            long staffId,
            LocalDate date,
            boolean confirmed,
            List<Long> plannedMenuIds) {}
}

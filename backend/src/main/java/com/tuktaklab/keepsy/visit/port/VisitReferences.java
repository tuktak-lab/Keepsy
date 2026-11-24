package com.tuktaklab.keepsy.visit.port;

import com.tuktaklab.keepsy.analytics.Attribution.SentAd;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 방문이 다른 도메인(예약 · 시술 메뉴 · 고객 · 발송 기록 · 매장 설정)에서 필요한 값과 동작 (DEVELOPMENT 4장 「도메인 연동」). 모두 세션 매장 조건
 * (NFR-03)
 */
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

    /** 그 매장의 삭제되지 않은 고객인지 (FR-CUS-10: 삭제된 고객의 상세 영역은 not_found) */
    boolean isActiveCustomer(long shopId, long customerId);

    /** 방문 삭제: 그 방문에 연결된 예약을 모두 확정으로 되돌린다 (F8) */
    void reopenReservations(long shopId, long visitId);

    /** 방문 수정: 그 방문에 연결된 예약 중 가장 이른 예약 ID (새로 더한 시술의 예약, F25). 없으면 null */
    Long earliestReservationId(long shopId, long visitId);

    /** 방문 삭제: 사진 행을 지우고, 파일은 트랜잭션이 커밋된 뒤 지운다 (F8, F16) */
    void deletePhotos(long shopId, long visitId);

    /** 그 매장 스탭 ID만 남긴다 (시술별 담당 스탭 확인) */
    Set<Long> shopStaffIds(long shopId, Collection<Long> staffIds);

    /** 방문이 생기거나 바뀌거나 지워진 고객의 예측을 같은 트랜잭션에서 다시 계산한다 (FR-PRD-01) */
    void recalculatePrediction(long shopId, long customerId);

    /** 고객이 받은 발송 성공 광고 메시지. 실패 · 대조군 · 안내 메시지는 빠진다 (FR-ANL-03, 명세서 5장 campaign_message) */
    List<SentAd> sentAds(long shopId, long customerId);

    /** 매장 설정의 귀속 기간(일) (FR-SET-04) */
    int attributionDays(long shopId);

    /**
     * @param visitId 완료된 예약이면 연결된 방문
     * @param startAt 예약 시작 시각. 방문의 캠페인 귀속 기준 시각이다 (FR-VIS-04)
     */
    record ReservationToClose(
            long id,
            Long customerId,
            long staffId,
            LocalDate date,
            boolean confirmed,
            boolean completed,
            Long visitId,
            List<Long> plannedMenuIds,
            LocalDateTime startAt) {}
}

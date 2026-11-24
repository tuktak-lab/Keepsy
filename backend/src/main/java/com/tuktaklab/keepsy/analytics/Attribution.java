package com.tuktaklab.keepsy.analytics;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 캠페인 귀속 판정과 승계 (FR-VIS-04, FR-ANL-03, A8, E7). 값만으로 결과가 정해지는 규칙이라 DB · 스프링 없이 단위 테스트한다
 * (DEVELOPMENT 4장). 귀속 발송 ID는 {@code campaign_message.id}다 (F15).
 *
 * <p>호출하는 쪽이 채우는 값: 그 고객이 받은 발송 중 <b>발송 성공한 광고 메시지</b>(실패 · 대조군 제외, 명세서 5장 campaign_message), 매장
 * 설정의 귀속 기간(FR-SET-04). CSV로 가져온 방문은 귀속 대상이 아니므로 이 규칙을 부르지 않는다 (FR-CUS-13). 시각은 Asia/Seoul
 * (NFR-12).
 */
public final class Attribution {

    private Attribution() {}

    // ponytail: 카테고리는 CONTRACTS 3장 「시술 카테고리」 enum 이름 문자열. 공용 enum이 생기면 그 타입으로 바꾼다
    /** 고객이 받은 발송 성공 광고 메시지 하나 */
    public record SentAd(long messageId, String category, LocalDateTime sentAt) {}

    /** 승계 후보: 같은 고객의 확정 · 완료 예약 중 아직 귀속이 없는 예정 시술 하나 */
    public record Candidate(long reservationServiceId, String category, LocalDateTime createdAt) {}

    /**
     * 예약 · 방문의 시술 하나를 어느 발송에 연결할지.
     *
     * @param category 그 시술의 카테고리
     * @param at 예약 생성 시각 (고객 없는 예약에 나중에 고객을 연결하면 그 예약의 생성 시각, E3)
     * @param sends 그 고객이 받은 발송 성공 광고 메시지
     * @param claimedMessageIds 이미 다른 예약 · 방문 시술이 귀속된 발송. 이미 연결된 발송이면 이번 것은 첫 예약 · 방문이 아니다
     * @param attributionDays 귀속 기간 (매장 설정, 기본 14일)
     * @return 같은 카테고리에서 {@code at} 이전 가장 최근 발송이 귀속 기간 안이고 아직 연결되지 않았으면 그 발송 ID
     */
    public static Optional<Long> attribute(
            String category,
            LocalDateTime at,
            List<SentAd> sends,
            Collection<Long> claimedMessageIds,
            int attributionDays) {
        requirePositive(attributionDays);
        return sends.stream()
                .filter(s -> s.category().equals(category) && !s.sentAt().isAfter(at))
                .max(Comparator.comparing(SentAd::sentAt))
                .filter(s -> withinPeriod(s, at, attributionDays))
                .map(SentAd::messageId)
                .filter(id -> !claimedMessageIds.contains(id));
    }

    /**
     * 귀속된 예약이 취소 · 노쇼되면 같은 발송을 이어받을 다음 예약 시술 (FR-ANL-03). 재예약 단계 수는 고객당 1로 유지된다.
     *
     * @param send 취소 · 노쇼된 예약 시술이 갖고 있던 발송
     * @param candidates 같은 고객의 확정 · 완료 예약 시술 중 귀속이 없는 것 (취소 · 노쇼된 그 예약은 빼고 넘긴다)
     * @return 같은 카테고리이고 발송 후 귀속 기간 안에 생성된 것 중 가장 먼저 생성된 예약 시술. 없으면 비어 있다
     */
    public static Optional<Long> successor(
            SentAd send, List<Candidate> candidates, int attributionDays) {
        requirePositive(attributionDays);
        return candidates.stream()
                .filter(c -> c.category().equals(send.category()))
                .filter(c -> !c.createdAt().isBefore(send.sentAt()))
                .filter(c -> withinPeriod(send, c.createdAt(), attributionDays))
                .min(Comparator.comparing(Candidate::createdAt))
                .map(Candidate::reservationServiceId);
    }

    /** 발송일부터 날짜 기준 귀속 기간 이내 (발송일 + N일까지 포함, A8 예시: 11/8 발송 → 11/20 연결 · 11/30 없음) */
    private static boolean withinPeriod(SentAd send, LocalDateTime at, int attributionDays) {
        return ChronoUnit.DAYS.between(send.sentAt().toLocalDate(), at.toLocalDate())
                <= attributionDays;
    }

    private static void requirePositive(int attributionDays) {
        if (attributionDays <= 0) {
            throw new IllegalArgumentException("귀속 기간은 0보다 커야 한다: " + attributionDays);
        }
    }
}

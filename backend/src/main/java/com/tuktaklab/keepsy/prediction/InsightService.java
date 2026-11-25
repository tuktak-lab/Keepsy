package com.tuktaklab.keepsy.prediction;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.prediction.query.InsightQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인사이트 목록 · 다음 액션 제안 (FR-PRD-05 · 06, CONTRACTS 6장 「예측 · 인사이트 (PRD)」) */
@Service
@RequiredArgsConstructor
public class InsightService {

    /** 인사이트 탭에 나오는 상태 (예측 없음 제외) */
    private static final List<PredictionStatus> TABS =
            List.of(
                    PredictionStatus.SCHEDULED,
                    PredictionStatus.DUE,
                    PredictionStatus.CHURN_SIGNAL,
                    PredictionStatus.LONG_ABSENT);

    /** 오늘 관리할 고객에 나오는 상태 (FR-DSH-02) */
    private static final List<PredictionStatus> CARE_TARGETS =
            List.of(PredictionStatus.DUE, PredictionStatus.CHURN_SIGNAL);

    private static final int CARE_TARGET_LIMIT = 10;

    /** CONTRACTS 6장 「`GET /api/insights` 항목 필드」 */
    public record Item(
            long customerId,
            String name,
            String category,
            PredictionStatus status,
            LocalDate lastServiceDate,
            long elapsedDays,
            LocalDate startDate,
            LocalDate endDate,
            boolean marketingConsent,
            NextAction nextAction,
            HandlingMark handlingMark,
            OffsetDateTime handlingMarkedAt) {}

    private final InsightQuery query;
    private final Clock clock;

    /**
     * @param status null이면 네 상태 모두
     * @param category null이면 모든 카테고리
     * @throws ApiException 상태가 예측 없음이면 {@code invalid}
     */
    @Transactional(readOnly = true)
    public PageResponse<Item> list(
            long shopId, PredictionStatus status, String category, int page, int size) {
        if (status == PredictionStatus.NO_PREDICTION) {
            throw new ApiException(ErrorCode.INVALID, "예측 없음은 인사이트에 나오지 않습니다");
        }
        List<PredictionStatus> statuses = status == null ? TABS : List.of(status);
        LocalDate today = LocalDate.now(clock);
        List<Item> items = items(shopId, statuses, category, today, page, size);
        return new PageResponse<>(
                items, page, size, query.count(shopId, statuses, category, today));
    }

    /** 오늘 관리할 고객: 주기 도래 · 이탈 신호 행 우선순위 상위 10행 (FR-DSH-02, C13, CONTRACTS 6장) */
    @Transactional(readOnly = true)
    public List<Item> careTargets(long shopId) {
        return items(shopId, CARE_TARGETS, null, LocalDate.now(clock), 0, CARE_TARGET_LIMIT);
    }

    private List<Item> items(
            long shopId,
            List<PredictionStatus> statuses,
            String category,
            LocalDate today,
            int page,
            int size) {
        return query.find(shopId, statuses, category, today, page, size).stream()
                .map(r ->
                        new Item(
                                r.customerId(),
                                r.name(),
                                r.category(),
                                r.status(),
                                r.lastServiceDate(),
                                ChronoUnit.DAYS.between(r.lastServiceDate(), today),
                                r.startDate(),
                                r.endDate(),
                                r.marketingConsent(),
                                NextAction.suggest(
                                                r.status(),
                                                r.marketingConsent(),
                                                r.marketingWithdrawn())
                                        .orElse(null),
                                r.handlingMark(),
                                TimeConfig.offset(r.handlingMarkedAt())))
                .toList();
    }
}

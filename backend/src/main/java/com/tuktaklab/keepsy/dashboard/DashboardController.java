package com.tuktaklab.keepsy.dashboard;

import com.tuktaklab.keepsy.analytics.SummaryPeriod;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.dashboard.query.BookingChannelQuery;
import com.tuktaklab.keepsy.dashboard.query.DashboardTodayQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 대시보드 (CONTRACTS 4장 「성과 · 대시보드 · 검색」). 권한은 {@code DASHBOARD_SEARCH}. */
@RestController
@RequestMapping("/api/dashboard")
@RequireFeature(FeatureKey.DASHBOARD_SEARCH)
@RequiredArgsConstructor
class DashboardController {

    private final DashboardTodayQuery todayQuery;
    private final BookingChannelQuery bookingChannelQuery;
    private final ShopSession shopSession;
    private final Clock clock;

    record BookingChannelsResponse(
            LocalDate from, LocalDate to, long total, List<BookingChannelQuery.Channel> channels) {}

    /** 오늘 예약 · 마감 안 된 예약 · 이번 주 요약 (FR-DSH-01 · 03 · 05) */
    @GetMapping("/today")
    DashboardTodayQuery.Today today() {
        return todayQuery.find(shopSession.shopId(), LocalDateTime.now(clock));
    }

    /** 예약 경로 통계 (FR-DSH-06, F27). 기간 필터는 매장 기본 지표와 같다 (F24) */
    @GetMapping("/booking-channels")
    BookingChannelsResponse bookingChannels(
            @RequestParam SummaryPeriod period,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        SummaryPeriod.Range range = period.range(LocalDate.now(clock), from, to);
        BookingChannelQuery.Result r =
                bookingChannelQuery.find(shopSession.shopId(), range.from(), range.to());
        return new BookingChannelsResponse(range.from(), range.to(), r.total(), r.channels());
    }
}

package com.tuktaklab.keepsy.reservation;

import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.reservation.query.ReservationCalendarQuery;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 가능 시간 · 등록 · 상세 · 취소 · 노쇼 · 고객 연결 (CONTRACTS 4장 「예약 · 시술 마감 · 결제 · 방문」). 권한은 {@code
 * RESERVATION}.
 */
@RestController
@RequestMapping("/api/reservations")
@RequireFeature(FeatureKey.RESERVATION)
@RequiredArgsConstructor
class ReservationController {

    private final ReservationAppService service;
    private final ShopSession shopSession;
    private final FeatureGuard featureGuard;
    private final ReservationCalendarQuery calendar;

    record AvailableTimesResponse(List<OffsetDateTime> startTimes) {}

    record CreateRequest(
            Long customerId,
            @NotNull Long staffId,
            @NotNull OffsetDateTime startAt,
            Integer minutes,
            ReservationChannel channel,
            Boolean consultFirst,
            @Size(max = 1000) String memo,
            @NotEmpty List<@NotNull Long> serviceMenuIds) {}

    record IdResponse(long id) {}

    record LinkCustomerRequest(@NotNull Long customerId) {}

    /** 예약 제한 고객이면 안내 문장, 아니면 null (FR-CUS-14, E3) */
    record LinkCustomerResponse(String notice) {}

    @GetMapping("/available-times")
    AvailableTimesResponse availableTimes(
            @RequestParam long staffId, @RequestParam LocalDate date, @RequestParam int minutes) {
        return new AvailableTimesResponse(
                service.availableTimes(shopId(), staffId, date, minutes).stream()
                        .map(time -> TimeConfig.offset(date.atTime(time)))
                        .toList());
    }

    record PeriodResponse(List<ReservationCalendarQuery.Item> reservations) {}

    /** 기간 예약 · 주간 캘린더 · 일간 보기 (FR-RSV-02 · 05). {@code to} 포함, 최대 7일 (F13) */
    @GetMapping
    PeriodResponse period(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return new PeriodResponse(calendar.find(shopId(), from, to));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IdResponse create(@Valid @RequestBody CreateRequest req, HttpSession session) {
        NewReservation in =
                new NewReservation(
                        req.customerId(),
                        req.staffId(),
                        req.startAt().atZoneSameInstant(TimeConfig.ZONE).toLocalDateTime(),
                        req.minutes(),
                        req.channel(),
                        Boolean.TRUE.equals(req.consultFirst()),
                        req.memo(),
                        req.serviceMenuIds());
        // 예약 제한 고객일 때만 원장 PIN이 필요하므로 여기서 막지 않고 인증 여부만 넘긴다
        return new IdResponse(service.create(shopId(), in, featureGuard.ownerPinVerified(session)));
    }

    @GetMapping("/{reservationId}")
    ReservationAppService.ReservationDetail detail(@PathVariable long reservationId) {
        return service.detail(shopId(), reservationId);
    }

    @PostMapping("/{reservationId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable long reservationId) {
        service.cancel(shopId(), reservationId);
    }

    @PostMapping("/{reservationId}/no-show")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void noShow(@PathVariable long reservationId) {
        service.markNoShow(shopId(), reservationId);
    }

    @PutMapping("/{reservationId}/customer")
    LinkCustomerResponse linkCustomer(
            @PathVariable long reservationId, @Valid @RequestBody LinkCustomerRequest req) {
        return new LinkCustomerResponse(
                service.linkCustomer(shopId(), reservationId, req.customerId()).orElse(null));
    }

    private long shopId() {
        return shopSession.shopId();
    }
}

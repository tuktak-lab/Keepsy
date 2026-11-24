package com.tuktaklab.keepsy.reservation;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.reservation.query.ReservationCalendarQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 상세의 「예약」 영역 (FR-CUS-03, CONTRACTS 4장 「고객 (CUS)」). 예약 정보라 고객 조회가 아니라 {@code RESERVATION} 설정을
 * 따른다 (명세서 2장).
 */
@RestController
@RequireFeature(FeatureKey.RESERVATION)
@RequiredArgsConstructor
class CustomerReservationController {

    private final ReservationCalendarQuery query;
    private final ShopSession shopSession;

    @GetMapping("/api/customers/{customerId}/reservations")
    PageResponse<ReservationCalendarQuery.Item> list(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return query.findByCustomer(shopSession.shopId(), customerId, page, size);
    }
}

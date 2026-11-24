package com.tuktaklab.keepsy.visit;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.visit.query.ClosingCardQuery;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 없이 완료 처리 · 시술 마감 카드 조회 · 저장 (CONTRACTS 6장 「방문 (VIS)」 · 「시술 마감 · 결제 (PAY)」). 경로는 예약 아래지만 방문 ·
 * 결제를 만드는 규칙이라 visit 패키지에 둔다 (ARCHITECTURE 4장). 권한은 {@code RESERVATION}.
 */
@RestController
@RequestMapping("/api/reservations/{reservationId}")
@RequireFeature(FeatureKey.RESERVATION)
@RequiredArgsConstructor
class VisitController {

    private final VisitAppService service;
    private final ShopSession shopSession;
    private final ClosingCardQuery closingCard;

    record CompleteRequest(List<@NotNull Long> serviceMenuIds) {}

    record ClosingItem(@NotNull Long serviceMenuId, @NotNull Long staffId, Boolean cycleExcluded) {}

    record ClosingRequest(
            List<@NotNull @Valid ClosingItem> services,
            @NotNull @Min(0) Integer amount,
            @NotNull PaymentMethod method,
            @Size(max = 1000) String memo,
            Boolean noCustomer) {}

    record VisitIdResponse(long visitId) {}

    /** 시술 마감 카드 채울 값 (FR-PAY-01 · 04) */
    @GetMapping("/closing")
    ClosingCardQuery.Card closingCard(@PathVariable long reservationId) {
        return closingCard.get(shopSession.shopId(), reservationId);
    }

    /** 본문을 통째로 빼도 된다 (예정 시술 그대로) */
    @PostMapping("/complete")
    VisitIdResponse complete(
            @PathVariable long reservationId,
            @Valid @RequestBody(required = false) CompleteRequest req) {
        return new VisitIdResponse(
                service.completeWithoutPayment(
                        shopSession.shopId(),
                        reservationId,
                        req == null ? null : req.serviceMenuIds()));
    }

    /** 메모 작성 스탭은 현재 근무자 (FR-ACC-10). RESERVATION 검사가 근무자를 이미 요구한다 */
    @PostMapping("/closing")
    VisitIdResponse close(
            @PathVariable long reservationId,
            @Valid @RequestBody ClosingRequest req,
            HttpSession session) {
        List<Closing.Item> services =
                req.services() == null
                        ? null
                        : req.services().stream()
                                .map(i ->
                                        new Closing.Item(
                                                i.serviceMenuId(),
                                                i.staffId(),
                                                Boolean.TRUE.equals(i.cycleExcluded())))
                                .toList();
        Closing in =
                new Closing(
                        services,
                        req.amount(),
                        req.method(),
                        req.memo(),
                        Boolean.TRUE.equals(req.noCustomer()));
        return new VisitIdResponse(
                service.close(
                        shopSession.shopId(),
                        reservationId,
                        in,
                        shopSession.currentStaffId(session)));
    }
}

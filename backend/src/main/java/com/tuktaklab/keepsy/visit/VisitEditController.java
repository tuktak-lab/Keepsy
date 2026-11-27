package com.tuktaklab.keepsy.visit;

import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.common.audit.AccessLogger.Action;
import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 방문 수정 · 삭제 (FR-VIS-03 · 05, F8, CONTRACTS 6장 「방문 (VIS)」). 권한은 {@code CUSTOMER_EDIT} */
@RestController
@RequestMapping("/api/visits/{visitId}")
@RequireFeature(FeatureKey.CUSTOMER_EDIT)
@RequiredArgsConstructor
class VisitEditController {

    private final VisitAppService service;
    private final ShopSession shopSession;
    private final FeatureGuard featureGuard;
    private final AccessLogger accessLogger;

    record ServiceItem(@NotNull Long serviceMenuId, @NotNull Long staffId, Boolean cycleExcluded) {}

    record EditRequest(@NotNull List<@NotNull @Valid ServiceItem> services) {}

    /** 방문의 실제 시술 · 담당 스탭 · 주기 계산 제외 수정 (FR-VIS-03 · 05, F25) */
    @PatchMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void edit(@PathVariable long visitId, @Valid @RequestBody EditRequest req) {
        Long customerId =
                service.editVisit(
                        shopSession.shopId(),
                        visitId,
                        req.services().stream()
                                .map(i ->
                                        new Closing.Item(
                                                i.serviceMenuId(),
                                                i.staffId(),
                                                Boolean.TRUE.equals(i.cycleExcluded())))
                                .toList());
        // 그 고객의 시술 기록 수정 (NFR-06). 고객 없음으로 마감한 방문은 개인정보 대상이 없어 남기지 않는다
        if (customerId != null) {
            accessLogger.record(
                    FeatureKey.CUSTOMER_EDIT, AccessLogger.CUSTOMER, customerId, Action.UPDATE);
        }
    }

    /**
     * 방문 삭제 = 그 방문의 마감 되돌리기 (F8). 취소되지 않은 결제가 있으면 결제를 함께 취소하므로 {@code PAYMENT_EDIT}도 검사한다
     * (CONTRACTS 4 · 6장).
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long visitId, HttpSession session) {
        long shopId = shopSession.shopId();
        if (service.hasActivePayments(shopId, visitId)) {
            featureGuard.check(session, FeatureKey.PAYMENT_EDIT);
        }
        long customerId = service.deleteVisit(shopId, visitId);
        // 그 고객의 시술 기록 · 사진 · 방문 메모 삭제 (NFR-06). 고객 없는 방문은 삭제가 409라 여기 오지 않는다 (E3)
        accessLogger.record(
                FeatureKey.CUSTOMER_EDIT, AccessLogger.CUSTOMER, customerId, Action.DELETE);
    }
}

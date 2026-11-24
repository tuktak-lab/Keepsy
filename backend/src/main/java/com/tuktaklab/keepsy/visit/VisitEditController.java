package com.tuktaklab.keepsy.visit;

import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 방문 수정 · 삭제 (FR-VIS-03, F8, CONTRACTS 6장 「방문 (VIS)」). 권한은 {@code CUSTOMER_EDIT} */
@RestController
@RequestMapping("/api/visits/{visitId}")
@RequireFeature(FeatureKey.CUSTOMER_EDIT)
@RequiredArgsConstructor
class VisitEditController {

    private final VisitAppService service;
    private final ShopSession shopSession;
    private final FeatureGuard featureGuard;

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
        // ponytail: 개인정보 삭제 접근 로그(NFR-06)는 기록 컴포넌트(#12)가 생기면 여기서 남긴다
        service.deleteVisit(shopId, visitId);
    }
}

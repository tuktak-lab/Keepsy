package com.tuktaklab.keepsy.visit;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 결제 수정 · 취소 (FR-PAY-02, F8, CONTRACTS 6장 「시술 마감 · 결제 (PAY)」). 권한은 {@code PAYMENT_EDIT} */
@RestController
@RequestMapping("/api/payments/{paymentId}")
@RequireFeature(FeatureKey.PAYMENT_EDIT)
@RequiredArgsConstructor
class PaymentController {

    private final VisitAppService service;
    private final ShopSession shopSession;

    record UpdateRequest(@NotNull @Min(0) Integer amount, @NotNull PaymentMethod method) {}

    @PatchMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@PathVariable long paymentId, @Valid @RequestBody UpdateRequest req) {
        service.updatePayment(shopSession.shopId(), paymentId, req.amount(), req.method());
    }

    /** 결제만 취소하고 방문은 그대로 둔다 (F8) */
    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable long paymentId) {
        service.cancelPayment(shopSession.shopId(), paymentId);
    }
}

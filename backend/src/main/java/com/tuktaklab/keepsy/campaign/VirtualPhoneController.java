package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 가상 휴대폰 (CONTRACTS 6장 「캠페인 · 발송 · 가상 휴대폰 (CMP)」). 기능 키 {@code CAMPAIGN_SEND} (기본 PIN) */
@RestController
@RequiredArgsConstructor
class VirtualPhoneController {

    private static final String PHONE = "010-?\\d{4}-?\\d{4}";
    private static final String PHONE_MESSAGE = "휴대폰 번호는 010으로 시작하는 11자리입니다";

    private final VirtualPhoneService service;
    private final ShopSession shopSession;

    /** 가상 수신함, 받은 시각 최신순 (FR-CMP-16, F23) */
    @RequireFeature(FeatureKey.CAMPAIGN_SEND)
    @GetMapping("/api/virtual-phone/messages")
    PageResponse<VirtualPhoneService.InboxItem> inbox(
            @RequestParam @Pattern(regexp = PHONE, message = PHONE_MESSAGE) String phone,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.inbox(shopSession.shopId(), phone, page, size);
    }

    record UnsubscribeRequest(
            @NotNull(message = "번호를 보내 주세요") @Pattern(regexp = PHONE, message = PHONE_MESSAGE)
                    String phone) {}

    /** [수신거부]: 마케팅 철회(수신 거부)와 처리 안내 자동 발송 (FR-CMP-11 · 16, F48) */
    @RequireFeature(FeatureKey.CAMPAIGN_SEND)
    @PostMapping("/api/virtual-phone/unsubscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(@Valid @RequestBody UnsubscribeRequest req) {
        service.unsubscribe(shopSession.shopId(), req.phone());
    }
}

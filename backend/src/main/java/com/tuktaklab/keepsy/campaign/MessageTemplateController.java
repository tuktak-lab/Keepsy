package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 메시지 템플릿 (CONTRACTS 6장 「캠페인 · 발송 · 가상 휴대폰 (CMP)」) */
@RestController
@RequiredArgsConstructor
class MessageTemplateController {

    private final MessageTemplateService service;
    private final ShopSession shopSession;

    @RequireFeature(FeatureKey.CAMPAIGN_VIEW)
    @GetMapping("/api/message-templates")
    PageResponse<MessageTemplateService.TemplateItem> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(shopSession.shopId(), page, size);
    }
}

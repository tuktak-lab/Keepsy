package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 메시지 템플릿 (CONTRACTS 6장 「캠페인 · 발송 · 가상 휴대폰 (CMP)」) */
@RestController
@RequiredArgsConstructor
class MessageTemplateController {

    private final MessageTemplateService service;
    private final ShopSession shopSession;

    /** 작성 · 수정 본문. 변수 · 바이트 규칙은 Service가 본다 (C10) */
    record TemplateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank String body,
            @NotNull TemplatePurpose purpose) {}

    record IdResponse(long id) {}

    @RequireFeature(FeatureKey.CAMPAIGN_VIEW)
    @GetMapping("/api/message-templates")
    PageResponse<MessageTemplateService.TemplateItem> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(shopSession.shopId(), page, size);
    }

    @RequireFeature(FeatureKey.CAMPAIGN_SEND)
    @PostMapping("/api/message-templates")
    @ResponseStatus(HttpStatus.CREATED)
    IdResponse create(@Valid @RequestBody TemplateRequest req) {
        return new IdResponse(
                service.create(shopSession.shopId(), req.name(), req.body(), req.purpose()));
    }

    @RequireFeature(FeatureKey.CAMPAIGN_SEND)
    @PatchMapping("/api/message-templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@PathVariable long templateId, @Valid @RequestBody TemplateRequest req) {
        service.update(shopSession.shopId(), templateId, req.name(), req.body(), req.purpose());
    }

    @RequireFeature(FeatureKey.CAMPAIGN_SEND)
    @DeleteMapping("/api/message-templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long templateId) {
        service.delete(shopSession.shopId(), templateId);
    }
}

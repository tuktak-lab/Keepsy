package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캠페인 즉시 발송 · 예약 · 취소 (CONTRACTS 4 · 6장 CMP, F51 · F56). 기존 CampaignController와 파일을 나눠 다른 PR과 겹치지
 * 않게 했다
 */
@RestController
@RequiredArgsConstructor
class CampaignSendController {

    private final CampaignSendService sendService;
    private final ShopSession shopSession;

    /** 즉시 발송 (FR-CMP-08). 가상 발송기로 처리하고 실제로 전송하지 않는다 (A6) */
    @RequireFeature(FeatureKey.CAMPAIGN_SEND)
    @PostMapping("/api/campaigns/{campaignId}/send")
    CampaignSendService.Result send(@PathVariable long campaignId) {
        return sendService.send(shopSession.shopId(), campaignId);
    }

    /**
     * @param scheduledAt 오프셋 포함 ISO 8601 (CONTRACTS 2장)
     */
    record ScheduleRequest(@NotNull OffsetDateTime scheduledAt) {}

    /** 예약 지정 · 다시 지정 (FR-CMP-09, F56) */
    @RequireFeature(FeatureKey.CAMPAIGN_SEND)
    @PutMapping("/api/campaigns/{campaignId}/schedule")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void schedule(@PathVariable long campaignId, @Valid @RequestBody ScheduleRequest r) {
        sendService.schedule(
                shopSession.shopId(),
                campaignId,
                r.scheduledAt().atZoneSameInstant(TimeConfig.ZONE).toLocalDateTime());
    }

    /** 사용자 취소 (F7 · F56) */
    @RequireFeature(FeatureKey.CAMPAIGN_SEND)
    @PostMapping("/api/campaigns/{campaignId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable long campaignId) {
        sendService.cancel(shopSession.shopId(), campaignId);
    }
}

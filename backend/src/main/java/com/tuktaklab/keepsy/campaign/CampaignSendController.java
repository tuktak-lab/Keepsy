package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 캠페인 즉시 발송 (CONTRACTS 4 · 6장 CMP, F51). 기존 CampaignController와 파일을 나눠 다른 PR과 겹치지 않게 했다 */
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
}

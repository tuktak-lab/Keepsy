package com.tuktaklab.keepsy.analytics;

import com.tuktaklab.keepsy.analytics.query.CampaignResultQuery;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대시보드의 최근 캠페인 성과 (FR-DSH-04, CONTRACTS 6장 「성과 · 대시보드 · 검색」). 재예약 정의를 퍼널과 같은 조회로 쓰려고 성과 패키지에 둔다
 * (F35, F36). 대시보드 자체는 {@code DASHBOARD_SEARCH}지만 이 영역은 "캠페인 목록 · 결과 조회" 설정을 따른다 (명세서 2장).
 */
@RestController
@RequiredArgsConstructor
class RecentCampaignController {

    private final CampaignResultQuery query;
    private final ShopSession shopSession;

    /** 재예약 전환율은 화면이 {@code rebookedCount ÷ reachedCount}로 계산한다 */
    @GetMapping("/api/dashboard/recent-campaigns")
    @RequireFeature(FeatureKey.CAMPAIGN_VIEW)
    List<CampaignResultQuery.RecentCampaign> recentCampaigns() {
        return query.recentCampaigns(shopSession.shopId());
    }
}

package com.tuktaklab.keepsy.customer.port;

import com.tuktaklab.keepsy.campaign.UnsubscribeNoticeService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** campaign 도메인이 공개한 {@link UnsubscribeNoticeService}로 보낸다 */
@Component
@RequiredArgsConstructor
class CampaignUnsubscribeNotice implements UnsubscribeNotice {

    private final UnsubscribeNoticeService notices;

    @Override
    public void send(
            long shopId, long customerId, String recipientPhone, LocalDateTime processedAt) {
        notices.send(shopId, customerId, recipientPhone, processedAt);
    }
}

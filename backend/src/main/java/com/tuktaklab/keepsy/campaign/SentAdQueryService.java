package com.tuktaklab.keepsy.campaign;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객이 받은 발송 성공 광고 메시지. 예약 · 방문의 캠페인 귀속(FR-VIS-04, FR-ANL-03)이 Port로 읽는다 (DEVELOPMENT 4장 「도메인 연동」).
 */
@Service
@RequiredArgsConstructor
public class SentAdQueryService {

    private final CampaignMessageRepository messages;

    /**
     * @param messageId 귀속 발송 ID (campaign_message.id, F15)
     * @param sentAt 발송 성공 시각 (result_at)
     */
    public record SentAd(long messageId, String category, LocalDateTime sentAt) {}

    /** 발송 성공(SENT)한 광고(AD) 중 대조군이 아니고 카테고리가 있는 것만 (명세서 5장 campaign_message, E2 · E7) */
    @Transactional(readOnly = true)
    public List<SentAd> sentAds(long shopId, long customerId) {
        return messages
                .findByShopIdAndCustomerIdAndKindAndSendStatusAndControlGroupFalseAndCategoryNotNull(
                        shopId, customerId, MessageKind.AD, SendStatus.SENT)
                .stream()
                .map(m -> new SentAd(m.getId(), m.getCategory(), m.getResultAt()))
                .toList();
    }
}

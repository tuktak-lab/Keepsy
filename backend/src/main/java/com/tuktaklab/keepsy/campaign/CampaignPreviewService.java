package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.port.ShopSendSettings;
import com.tuktaklab.keepsy.campaign.port.ShopSendSettings.ShopContact;
import com.tuktaklab.keepsy.campaign.query.CampaignRecipientQuery;
import com.tuktaklab.keepsy.campaign.query.CampaignRecipientQuery.Recipient;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.web.PageResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 캠페인 미리보기 (FR-CMP-05 · 18, B3, 결정 F46 · F47). 저장하지 않고 부를 때마다 다시 치환한다 */
@Service
@RequiredArgsConstructor
public class CampaignPreviewService {

    private final CampaignRepository campaigns;
    private final MessageTemplateRepository templates;
    private final CampaignRecipientQuery recipients;
    private final ShopSendSettings shopSettings;
    private final MessageSender sender;
    private final Clock clock;

    /** CONTRACTS 6장 「`items` 항목 필드」 */
    public record Item(
            long customerId,
            String customerName,
            String text,
            Integer bytes,
            MessageType messageType,
            List<String> missingVariables) {}

    /** CONTRACTS 6장 「`GET /api/campaigns/{campaignId}/preview` 응답 필드」 */
    public record Preview(
            int sendCount,
            int controlCount,
            long estimatedCost,
            long unsendableCount,
            PageResponse<Item> items) {}

    /**
     * @throws ApiException 다른 매장 · 없는 캠페인이면 {@code not_found}, 광고인데 매장 전화번호 · 수신 거부 번호가 없으면 {@code
     *     conflict} (F47)
     */
    @Transactional(readOnly = true)
    public Preview preview(long shopId, long campaignId, int page, int size) {
        Campaign campaign =
                campaigns
                        .findByIdAndShopId(campaignId, shopId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        String body = templates.findById(campaign.getTemplateId()).orElseThrow().getBody();
        ShopContact shop = shopSettings.contact(shopId);
        boolean ad = campaign.getMessageKind() == TemplatePurpose.MessageKind.AD;
        MessageKind kind = ad ? MessageKind.AD : MessageKind.CONSENT_RECONFIRM;
        AdSender adSender = ad ? shop.adSender() : null;
        LocalDate today = LocalDate.now(clock);

        List<Recipient> all = recipients.find(shopId, campaignId, campaign.getCategory());
        // ponytail: 발송 불가 인원을 세려고 대상 전체를 매번 치환한다. 대상이 수만 명이 되면 페이지만 치환하고 개수는 따로 센다
        List<Item> items =
                all.stream()
                        .filter(r -> !r.controlGroup())
                        .map(r -> item(r, body, kind, shop.name(), adSender, today))
                        .toList();
        long unsendable = items.stream().filter(i -> i.messageType() == null).count();
        int from = (int) Math.min((long) page * size, items.size());
        int to = Math.min(from + size, items.size());
        return new Preview(
                items.size(),
                all.size() - items.size(),
                sender.cost(shopId, items.size()),
                unsendable,
                new PageResponse<>(items.subList(from, to), page, size, items.size()));
    }

    private static Item item(
            Recipient r,
            String body,
            MessageKind kind,
            String shopName,
            AdSender adSender,
            LocalDate today) {
        MessageRenderer.Rendered m =
                MessageRenderer.render(body, kind, shopName, adSender, r, today);
        return new Item(
                r.customerId(),
                r.name(),
                m.text(),
                m.bytes(),
                m.messageType(),
                m.missingVariables());
    }
}

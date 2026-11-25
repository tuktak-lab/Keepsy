package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.MessageSender.Outgoing;
import com.tuktaklab.keepsy.campaign.port.ShopSendSettings;
import com.tuktaklab.keepsy.campaign.port.ShopSendSettings.ShopContact;
import com.tuktaklab.keepsy.campaign.query.CampaignRecipientQuery;
import com.tuktaklab.keepsy.campaign.query.CampaignRecipientQuery.Recipient;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 즉시 발송 (FR-CMP-08 · 18, A6, F7 · F51). 상태 전이(조건부 UPDATE)와 고객별 발송 기록 생성을 한 트랜잭션에서 한다. 본문은 미리보기와 같은
 * 규칙으로 만든다(MessageRenderer, F47). 실제 문자는 보내지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CampaignSendService {

    /** 발송 시점 재확인으로 빠진 사유 (F51) */
    static final String NO_MARKETING_CONSENT = "마케팅 동의 철회";

    static final String DELETED_CUSTOMER = "삭제된 고객";
    static final String UNSENDABLE = "발송 불가";

    private final CampaignRepository campaigns;
    private final MessageTemplateRepository templates;
    private final CampaignMessageRepository messages;
    private final CampaignRecipientQuery recipients;
    private final ShopSendSettings shopSettings;
    private final MessageSender sender;
    private final Clock clock;

    /** CONTRACTS 6장 즉시 발송 응답 */
    public record Result(int sendCount, int skippedCount) {}

    /**
     * @throws ApiException 다른 매장 · 없는 캠페인 {@code not_found}, 검토 기록 없음 {@code review_required}, 야간
     *     제한에 걸리면 {@code night_send_blocked} (F52), 일 발송 한도를 넘으면 {@code daily_limit_exceeded}
     *     (F54), 광고인데 매장 전화번호 · 수신 거부 번호가 없거나 이미 발송 중 · 완료 · 취소면 {@code conflict}
     */
    @Transactional
    public Result send(long shopId, long campaignId) {
        // 일 발송 한도용 매장 행 잠금은 트랜잭션의 첫 문장이어야 한다 (F7 · F54). MySQL REPEATABLE READ는 첫 일반 조회 때 스냅샷을
        // 잡으므로,
        // 다른 조회 뒤에 잠그면 앞선 동시 발송이 커밋한 기록이 "오늘 건수"에 보이지 않아 둘 다 한도를 통과한다. 잠금(locking read)은
        // 스냅샷을 잡지 않아, 잠금을 받은 뒤의 조회는 앞 발송의 커밋을 본다
        int limit = shopSettings.lockDailySendLimit(shopId);
        Campaign campaign =
                campaigns
                        .findByIdAndShopId(campaignId, shopId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        campaign.requireReviewed();
        String body = templates.findById(campaign.getTemplateId()).orElseThrow().getBody();
        ShopContact shop = shopSettings.contact(shopId);
        boolean ad = campaign.getMessageKind() == TemplatePurpose.MessageKind.AD;
        MessageKind kind = ad ? MessageKind.AD : MessageKind.CONSENT_RECONFIRM;
        AdSender adSender = ad ? shop.adSender() : null;
        LocalDateTime now = LocalDateTime.now(clock);

        // 누구에게 보내고 누구를 빼는지 먼저 정한다. 야간 · 한도 검사가 이 결과를 보고, 막히면 상태 · 기록을 바꾸지 않는다
        String category = campaign.getCategory();
        Map<Long, String> phones = recipients.phones(shopId, campaignId);
        LocalDate today = now.toLocalDate();
        List<CampaignMessage> notSent = new ArrayList<>();
        List<Outgoing> outgoing = new ArrayList<>();
        boolean allNightAdConsent = true;
        for (Recipient r : recipients.find(shopId, campaignId, category)) {
            long id = r.customerId();
            if (r.controlGroup()) {
                notSent.add(CampaignMessage.control(shopId, campaignId, kind, id, category));
                continue;
            }
            String phone = phones.get(id);
            String reason = skipReason(r);
            MessageRenderer.Rendered m =
                    reason == null
                            ? MessageRenderer.render(body, kind, shop.name(), adSender, r, today)
                            : null;
            if (reason == null && !m.sendable()) {
                reason = UNSENDABLE;
            }
            if (reason != null) {
                notSent.add(
                        CampaignMessage.skipped(
                                shopId, campaignId, kind, id, category, phone, reason, now));
                continue;
            }
            allNightAdConsent &= r.nightAdConsent();
            outgoing.add(
                    new Outgoing(
                            shopId,
                            campaignId,
                            kind,
                            id,
                            category,
                            phone,
                            new ComposedMessage(m.text(), m.bytes())));
        }

        // 야간 제한: 광고이고 실제로 보낼 고객 전원이 야간 광고 동의일 때만 예외 (FR-CMP-07, C11, F52)
        if (!NightSendPolicy.allows(now, ad, allNightAdConsent)) {
            throw new ApiException(ErrorCode.NIGHT_SEND_BLOCKED);
        }
        // 일 발송 한도: 맨 처음 잡은 매장 행 잠금 안에서 오늘 건수 + 이번 인원을 비교한다 (FR-CMP-12, F7 · F54)
        long sentToday =
                messages.countSentBetween(
                        shopId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        if (!DailyLimitPolicy.allows(sentToday, outgoing.size(), limit)) {
            throw new ApiException(ErrorCode.DAILY_LIMIT_EXCEEDED);
        }

        // 중복 발송 방지: 바뀐 행이 1일 때만 진행한다. 두 번째 요청은 첫 요청이 커밋될 때까지 기다린 뒤 0을 받는다 (F7)
        if (campaigns.startSending(campaignId, shopId, now) != 1) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 발송했거나 취소된 캠페인입니다");
        }
        messages.saveAll(notSent);
        sender.send(outgoing);
        int skipped = (int) notSent.stream().filter(row -> !row.isControlGroup()).count();
        if (outgoing.isEmpty()) {
            // 보낼 고객이 없으면 대기 행이 없으므로 바로 발송 완료 (F51)
            campaigns.finishSent();
        }
        return new Result(outgoing.size(), skipped);
    }

    /** 발송 시점 재확인 (F7 · F51). 대상을 고른 뒤 바뀐 동의 · 삭제를 다시 본다. null이면 보낼 수 있다 */
    static String skipReason(Recipient r) {
        if (r.deleted()) {
            return DELETED_CUSTOMER;
        }
        if (r.marketingConsentAt() == null) {
            return NO_MARKETING_CONSENT;
        }
        return null;
    }
}

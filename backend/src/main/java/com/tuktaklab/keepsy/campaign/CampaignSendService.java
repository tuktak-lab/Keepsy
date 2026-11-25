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
 * 즉시 발송 · 예약 발송 (FR-CMP-08 · 09 · 18, A6, F7 · F51 · F56). 상태 전이(조건부 UPDATE)와 고객별 발송 기록 생성을 한
 * 트랜잭션에서 한다. 본문은 미리보기와 같은 규칙으로 만든다(MessageRenderer, F47). 실제 문자는 보내지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CampaignSendService {

    /** 발송 시점 재확인으로 빠진 사유 (F51) */
    static final String NO_MARKETING_CONSENT = "마케팅 동의 철회";

    static final String DELETED_CUSTOMER = "삭제된 고객";
    static final String UNSENDABLE = "발송 불가";

    /** 야간 예약 광고 캠페인에서 발송 시점에 야간 광고 동의가 없는 고객 (F56) */
    static final String NO_NIGHT_AD_CONSENT = "야간 광고 동의 없음";

    private static final List<Campaign.Status> NOT_STARTED =
            List.of(Campaign.Status.DRAFT, Campaign.Status.SCHEDULED);

    private final CampaignRepository campaigns;
    private final MessageTemplateRepository templates;
    private final CampaignMessageRepository messages;
    private final CampaignRecipientQuery recipients;
    private final ShopSendSettings shopSettings;
    private final MessageSender sender;
    private final Clock clock;

    /** CONTRACTS 6장 즉시 발송 응답 */
    public record Result(int sendCount, int skippedCount) {}

    /** 누구에게 보내고 누구를 빼는지. 야간 · 한도 검사가 이 결과를 보고, 막히면 상태 · 기록을 바꾸지 않는다 */
    private record Plan(
            boolean ad,
            List<CampaignMessage> notSent,
            List<Outgoing> outgoing,
            boolean allNightAdConsent) {

        int skipped() {
            return (int) notSent.stream().filter(row -> !row.isControlGroup()).count();
        }
    }

    /**
     * @throws ApiException 다른 매장 · 없는 캠페인 {@code not_found}, 이미 발송 중 · 완료 · 취소면 {@code conflict},
     *     검토 기록 없음 {@code review_required}, 야간 제한에 걸리면 {@code night_send_blocked} (F52), 일 발송 한도를
     *     넘으면 {@code daily_limit_exceeded} (F54), 광고인데 매장 전화번호 · 수신 거부 번호가 없으면 {@code conflict}
     */
    @Transactional
    public Result send(long shopId, long campaignId) {
        // 일 발송 한도용 매장 행 잠금은 트랜잭션의 첫 문장이어야 한다 (F7 · F54). MySQL REPEATABLE READ는 첫 일반 조회 때 스냅샷을
        // 잡으므로, 다른 조회 뒤에 잠그면 앞선 동시 발송이 커밋한 기록이 "오늘 건수"에 보이지 않아 둘 다 한도를 통과한다.
        // 잠금(locking read)은 스냅샷을 잡지 않아, 잠금을 받은 뒤의 조회는 앞 발송의 커밋을 본다
        int limit = shopSettings.lockDailySendLimit(shopId);
        Campaign campaign = find(shopId, campaignId);
        campaign.requireNotStarted();
        campaign.requireReviewed();
        LocalDateTime now = LocalDateTime.now(clock);
        ShopContact shop = shopSettings.contact(shopId);
        Plan plan = plan(campaign, shop.name(), adSender(campaign, shop), now, false);

        // 야간 제한: 광고이고 실제로 보낼 고객 전원이 야간 광고 동의일 때만 예외 (FR-CMP-07, C11, F52)
        if (!NightSendPolicy.allows(now, plan.ad(), plan.allNightAdConsent())) {
            throw new ApiException(ErrorCode.NIGHT_SEND_BLOCKED);
        }
        // 일 발송 한도: 맨 처음 잡은 매장 행 잠금 안에서 오늘 건수 + 이번 인원을 비교한다 (FR-CMP-12, F7 · F54)
        if (!withinDailyLimit(shopId, now, plan, limit)) {
            throw new ApiException(ErrorCode.DAILY_LIMIT_EXCEEDED);
        }
        // 중복 발송 방지: 바뀐 행이 1일 때만 진행한다. 두 번째 요청은 첫 요청이 커밋될 때까지 기다린 뒤 0을 받는다 (F7)
        if (campaigns.startSending(campaignId, shopId, now) != 1) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 발송했거나 취소된 캠페인입니다");
        }
        start(plan);
        return new Result(plan.outgoing().size(), plan.skipped());
    }

    /**
     * 예약 지정 · 다시 지정 (FR-CMP-09, F56). 일 발송 한도는 발송 시점에 본다.
     *
     * @param at 예약 시각 (서울)
     * @throws ApiException 다른 매장 · 없는 캠페인 {@code not_found}, 이미 발송 중 · 완료 · 취소면 {@code conflict},
     *     검토 기록 없음 {@code review_required}, 지금보다 뒤가 아니면 {@code invalid}, 야간 시각을 고를 수 없으면 {@code
     *     night_send_blocked}, 광고인데 매장 전화번호 · 수신 거부 번호가 없으면 {@code conflict}
     */
    @Transactional
    public void schedule(long shopId, long campaignId, LocalDateTime at) {
        Campaign campaign = find(shopId, campaignId);
        campaign.requireNotStarted();
        campaign.requireReviewed();
        LocalDateTime now = LocalDateTime.now(clock);
        if (!at.isAfter(now)) {
            throw new ApiException(ErrorCode.INVALID, "예약 시각은 지금보다 뒤여야 합니다");
        }
        ShopContact shop = shopSettings.contact(shopId);
        Plan plan = plan(campaign, shop.name(), adSender(campaign, shop), now, false);
        // 야간 시각은 즉시 발송과 같은 규칙: 지정하는 지금 보낼 고객 전원이 야간 광고 동의인 광고만 (FR-CMP-07 · 09, F52)
        if (!NightSendPolicy.allows(at, plan.ad(), plan.allNightAdConsent())) {
            throw new ApiException(ErrorCode.NIGHT_SEND_BLOCKED);
        }
        if (campaigns.schedule(campaignId, shopId, at) != 1) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 발송했거나 취소된 캠페인입니다");
        }
    }

    /**
     * 사용자 취소 (F7 · F56)
     *
     * @throws ApiException 다른 매장 · 없는 캠페인 {@code not_found}, 이미 발송 중 · 완료 · 취소면 {@code conflict}
     */
    @Transactional
    public void cancel(long shopId, long campaignId) {
        find(shopId, campaignId);
        if (campaigns.cancel(
                        campaignId,
                        shopId,
                        NOT_STARTED,
                        Campaign.CancelReason.USER_CANCELED,
                        LocalDateTime.now(clock))
                != 1) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 발송했거나 취소된 캠페인입니다");
        }
    }

    /**
     * 예약 시각이 된 캠페인을 보낸다 (스케줄러, F56). 즉시 발송과 같은 순서로 검사하되, 막히면 오류 대신 발송 기록 없이 취소한다. 그 사이 취소 · 즉시 발송 ·
     * 다시 지정됐으면 아무것도 하지 않는다.
     */
    @Transactional
    public void sendScheduled(long shopId, long campaignId) {
        int limit = shopSettings.lockDailySendLimit(shopId); // 첫 문장이어야 한다 (send 참고)
        LocalDateTime now = LocalDateTime.now(clock);
        Campaign campaign = campaigns.findByIdAndShopId(campaignId, shopId).orElse(null);
        if (campaign == null
                || campaign.getStatus() != Campaign.Status.SCHEDULED
                || campaign.getScheduledAt().isAfter(now)) {
            return;
        }
        ShopContact shop = shopSettings.contact(shopId);
        AdSender adSender;
        try {
            adSender = adSender(campaign, shop);
        } catch (ApiException e) {
            cancelScheduled(campaign, Campaign.CancelReason.SHOP_INFO_MISSING, now);
            return;
        }
        // 야간 여부는 처리 시각이 아니라 예약 시각으로 본다. 야간 예약 광고는 야간 광고 동의가 없어진 고객만 뺀다
        boolean night = NightSendPolicy.isNight(campaign.getScheduledAt());
        Plan plan = plan(campaign, shop.name(), adSender, now, night);
        if (!withinDailyLimit(shopId, now, plan, limit)) {
            // 다음 날로 미루거나 다시 시도하지 않는다 (F7)
            cancelScheduled(campaign, Campaign.CancelReason.DAILY_LIMIT_EXCEEDED, now);
            return;
        }
        if (campaigns.startSending(campaignId, shopId, now) == 1) {
            start(plan);
        }
    }

    private void cancelScheduled(
            Campaign campaign, Campaign.CancelReason reason, LocalDateTime now) {
        campaigns.cancel(
                campaign.getId(),
                campaign.getShopId(),
                List.of(Campaign.Status.SCHEDULED),
                reason,
                now);
    }

    private Campaign find(long shopId, long campaignId) {
        return campaigns
                .findByIdAndShopId(campaignId, shopId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** 광고면 광고 표기용 매장 정보, 안내면 null (F47). 광고인데 번호가 비었으면 {@code conflict} */
    private static AdSender adSender(Campaign campaign, ShopContact shop) {
        return campaign.getMessageKind() == TemplatePurpose.MessageKind.AD ? shop.adSender() : null;
    }

    /**
     * @param shopName {매장명} 치환 (F46)
     * @param adSender 광고 캠페인이면 광고 표기용 매장 정보, 안내면 null
     * @param skipNoNightAdConsent 야간 예약 광고: 야간 광고 동의가 없는 고객을 빼고 보낸다 (F56)
     */
    private Plan plan(
            Campaign campaign,
            String shopName,
            AdSender adSender,
            LocalDateTime now,
            boolean skipNoNightAdConsent) {
        long shopId = campaign.getShopId();
        long campaignId = campaign.getId();
        boolean ad = campaign.getMessageKind() == TemplatePurpose.MessageKind.AD;
        MessageKind kind = ad ? MessageKind.AD : MessageKind.CONSENT_RECONFIRM;
        String body = templates.findById(campaign.getTemplateId()).orElseThrow().getBody();
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
            if (reason == null && ad && skipNoNightAdConsent && !r.nightAdConsent()) {
                reason = NO_NIGHT_AD_CONSENT;
            }
            MessageRenderer.Rendered m =
                    reason == null
                            ? MessageRenderer.render(body, kind, shopName, adSender, r, today)
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
        return new Plan(ad, notSent, outgoing, allNightAdConsent);
    }

    /** 오늘(서울) 실제로 발송기에 넘긴 건수 + 이번 인원 ≤ 한도 (F54). 매장 행 잠금 안에서 부른다 */
    private boolean withinDailyLimit(long shopId, LocalDateTime now, Plan plan, int limit) {
        LocalDate today = now.toLocalDate();
        long sentToday =
                messages.countSentBetween(
                        shopId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        return DailyLimitPolicy.allows(sentToday, plan.outgoing().size(), limit);
    }

    /** 상태 전이(startSending)가 1을 돌려준 뒤에 부른다 */
    private void start(Plan plan) {
        messages.saveAll(plan.notSent());
        sender.send(plan.outgoing());
        if (plan.outgoing().isEmpty()) {
            // 보낼 고객이 없으면 대기 행이 없으므로 바로 발송 완료 (F51)
            campaigns.finishSent();
        }
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

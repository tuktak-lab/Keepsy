package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.CampaignTargets.Target;
import com.tuktaklab.keepsy.campaign.query.CampaignQuery;
import com.tuktaklab.keepsy.campaign.query.CampaignTargetQuery.Selection;
import com.tuktaklab.keepsy.campaign.query.ConsentReconfirmQuery;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler.FieldViolation;
import com.tuktaklab.keepsy.common.web.PageResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 캠페인 만들기 · 대조군 · 검토 체크 (FR-CMP-01 · 03 · 06 · 18, E2, 결정 F41) */
@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignRepository campaigns;
    private final MessageTemplateRepository templates;
    private final CampaignTargets targets;
    private final NamedParameterJdbcTemplate jdbc;
    private final ConsentReconfirmQuery reconfirmQuery;
    private final CampaignQuery campaignQuery;
    private final Clock clock;

    /**
     * 대상을 다시 골라 {@code campaign_target}에 저장하고, 광고면 제외 후 대상 수 × 비율을 내림한 인원을 무작위로 대조군으로 정한다. 대조군
     * 행(campaign_message)은 발송을 시작할 때 만든다 (F7, F41).
     *
     * @param selection 대상 칸. 메시지 종류는 무시하고 템플릿 용도로 정한다
     * @return 만든 캠페인 ID (상태 DRAFT)
     * @throws ApiException 다른 매장 · 없는 템플릿이면 {@code not_found}, 안내 캠페인에 대조군 비율 · 고르기 조건 위반 · 제외 후 대상
     *     0명이면 {@code invalid}
     */
    @Transactional
    public long create(
            long shopId, String name, long templateId, int controlPercent, Selection selection) {
        // 템플릿 수정(F43)과 차례를 정하려고 템플릿 행을 잠근다. 수정이 먼저면 고친 템플릿으로 만든다
        MessageTemplate template =
                templates
                        .findForUpdateByIdAndShopId(templateId, shopId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        boolean info = template.getPurpose().messageKind() == TemplatePurpose.MessageKind.INFO;
        if (info && controlPercent != 0) {
            throw invalid("controlPercent", "동의 재확인 안내에는 대조군을 두지 않습니다");
        }
        List<Target> chosen =
                targets.select(
                                shopId,
                                selection.withKind(
                                        info ? MessageKind.CONSENT_RECONFIRM : MessageKind.AD))
                        .targets();
        if (chosen.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID, "제외하고 나면 보낼 대상이 없습니다");
        }
        Campaign campaign =
                campaigns.save(
                        Campaign.draft(
                                shopId, name, selection.category(), template, controlPercent));
        List<Target> shuffled = new ArrayList<>(chosen);
        Collections.shuffle(shuffled);
        int controlCount = chosen.size() * controlPercent / 100; // 내림 (F41)
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < shuffled.size(); i++) {
            rows.add(
                    Map.of(
                            "shopId",
                            shopId,
                            "campaignId",
                            campaign.getId(),
                            "customerId",
                            shuffled.get(i).customerId(),
                            "control",
                            i < controlCount));
        }
        jdbc.batchUpdate(
                "INSERT INTO campaign_target (shop_id, campaign_id, customer_id, control_group)"
                        + " VALUES (:shopId, :campaignId, :customerId, :control)",
                SqlParameterSourceUtils.createBatch(rows));
        return campaign.getId();
    }

    /**
     * 검토 체크 (FR-CMP-06 · 13). 작성 중일 때만 검토자 · 시각을 기록하고, 다시 체크하면 새로 쓴다.
     *
     * @param staffId 잠긴 상태면 PIN 인증 스탭, 공용이면 현재 근무자 (ARCHITECTURE 6장)
     * @throws ApiException 다른 매장 · 없는 캠페인이면 {@code not_found}, 작성 중이 아니면 {@code conflict}
     */
    @Transactional
    public void review(long shopId, long campaignId, long staffId) {
        if (campaigns.review(campaignId, shopId, staffId, LocalDateTime.now(clock)) == 1) {
            return;
        }
        throw campaigns.existsByIdAndShopId(campaignId, shopId)
                ? new ApiException(ErrorCode.CONFLICT, "작성 중인 캠페인만 검토할 수 있습니다")
                : new ApiException(ErrorCode.NOT_FOUND);
    }

    /** 캠페인 목록 (FR-CMP-10). 최근 만든 순 */
    @Transactional(readOnly = true)
    public PageResponse<CampaignQuery.Item> list(long shopId, int page, int size) {
        return new PageResponse<>(
                campaignQuery.find(shopId, page, size), page, size, campaignQuery.count(shopId));
    }

    /** CONTRACTS 6장 「`GET /api/campaigns/{campaignId}` 응답 필드」: 목록 항목 필드 + 상세 칸 + 고객별 결과 */
    public record Detail(
            long id,
            String name,
            String category,
            String messageKind,
            String status,
            int targetCount,
            int controlCount,
            OffsetDateTime scheduledAt,
            OffsetDateTime sentAt,
            String cancelReason,
            long templateId,
            int controlPercent,
            CampaignQuery.StaffRef reviewedBy,
            OffsetDateTime reviewedAt,
            OffsetDateTime canceledAt,
            PageResponse<CampaignQuery.Message> messages) {}

    /**
     * 캠페인 상세 · 고객별 발송 결과 (FR-CMP-10 · 18). 대조군은 발송 상태 없이 구분하고, 삭제된 고객은 표시만 한다 (FR-CUS-10).
     *
     * @throws ApiException 다른 매장 · 없는 캠페인이면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public Detail detail(long shopId, long campaignId, int page, int size) {
        CampaignQuery.Header h =
                campaignQuery
                        .findOne(shopId, campaignId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        CampaignQuery.Item i = h.item();
        CampaignQuery.Extra e = h.extra();
        return new Detail(
                i.id(),
                i.name(),
                i.category(),
                i.messageKind(),
                i.status(),
                i.targetCount(),
                i.controlCount(),
                i.scheduledAt(),
                i.sentAt(),
                i.cancelReason(),
                e.templateId(),
                e.controlPercent(),
                e.reviewedBy(),
                e.reviewedAt(),
                e.canceledAt(),
                new PageResponse<>(
                        campaignQuery.messages(shopId, campaignId, page, size),
                        page,
                        size,
                        campaignQuery.countMessages(shopId, campaignId)));
    }

    /** 동의 재확인 대상 (FR-CMP-13, D1, F45). 오늘은 Asia/Seoul (NFR-12) */
    @Transactional(readOnly = true)
    public PageResponse<ConsentReconfirmQuery.Row> reconfirmTargets(
            long shopId, int page, int size) {
        LocalDate today = LocalDate.now(clock);
        return new PageResponse<>(
                reconfirmQuery.find(shopId, today, page, size),
                page,
                size,
                reconfirmQuery.count(shopId, today));
    }

    /**
     * 고객 삭제 (FR-CUS-10, C12): 그 고객 발송 기록의 수신 번호 · 치환 본문을 비운다. 발송 결과 · 귀속 통계는 남긴다. 부르는 쪽 트랜잭션에서 함께
     * 커밋된다.
     */
    @Transactional
    public void eraseRecipient(long shopId, long customerId) {
        jdbc.update(
                "UPDATE campaign_message SET recipient_phone = NULL, body = NULL"
                        + " WHERE shop_id = :shopId AND customer_id = :customerId",
                Map.of("shopId", shopId, "customerId", customerId));
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(
                ErrorCode.INVALID, reason, List.of(new FieldViolation(field, reason)));
    }
}

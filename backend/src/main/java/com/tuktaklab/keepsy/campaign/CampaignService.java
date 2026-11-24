package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.CampaignTargets.Target;
import com.tuktaklab.keepsy.campaign.query.CampaignTargetQuery.Selection;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler.FieldViolation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 캠페인 만들기 · 대조군 (FR-CMP-01 · 03 · 18, E2, 결정 F41) */
@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignRepository campaigns;
    private final MessageTemplateRepository templates;
    private final CampaignTargets targets;
    private final NamedParameterJdbcTemplate jdbc;

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
        MessageTemplate template =
                templates
                        .findById(templateId)
                        .filter(t -> t.getShopId() == shopId)
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

    private static ApiException invalid(String field, String reason) {
        return new ApiException(
                ErrorCode.INVALID, reason, List.of(new FieldViolation(field, reason)));
    }
}

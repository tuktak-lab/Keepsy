package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.query.CampaignTargetQuery.Selection;
import com.tuktaklab.keepsy.campaign.query.ConsentReconfirmQuery;
import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.prediction.PredictionStatus;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 캠페인 API (CONTRACTS 4 · 6장 「캠페인 · 발송 · 가상 휴대폰 (CMP)」) */
@RestController
@RequireFeature(FeatureKey.CAMPAIGN_SEND)
@RequiredArgsConstructor
class CampaignController {

    private final CampaignTargets targets;
    private final CampaignService campaigns;
    private final ShopSession shopSession;
    private final FeatureGuard featureGuard;

    /** 대상 고르기 (CONTRACTS 6장). 캠페인 만들기도 같은 칸을 받는다 */
    record TargetsRequest(
            MessageKind kind,
            @Pattern(regexp = "NAIL|LASH|WAXING|SEMI_PERMANENT|ETC", message = "시술 카테고리 값이 아닙니다")
                    String category,
            @Size(max = 3000, message = "고객은 3,000명까지 고를 수 있습니다") List<@NotNull Long> customerIds,
            @Min(value = 0, message = "방문 횟수는 0 이상입니다") Integer visitCountMin,
            @Min(value = 0, message = "방문 횟수는 0 이상입니다") Integer visitCountMax,
            PredictionStatus predictionStatus,
            LocalDate lastServiceFrom,
            LocalDate lastServiceTo) {

        Selection selection() {
            return new Selection(
                    kind == null ? MessageKind.AD : kind,
                    category,
                    customerIds,
                    visitCountMin,
                    visitCountMax,
                    predictionStatus,
                    lastServiceFrom,
                    lastServiceTo);
        }
    }

    record TargetsResponse(int targetCount, Map<ExclusionReason, Integer> excludedCounts) {}

    /** 대상 미리 계산 (FR-CMP-01 · 02). 저장하지 않는다 */
    @PostMapping("/api/campaigns/targets")
    TargetsResponse previewTargets(@Valid @RequestBody TargetsRequest r) {
        CampaignTargets.Result result = targets.select(shopSession.shopId(), r.selection());
        return new TargetsResponse(result.targets().size(), result.excludedCounts());
    }

    /** 캠페인 만들기 (CONTRACTS 6장). 대상 칸은 TargetsRequest와 같고 {@code kind}는 받지 않는다 */
    record CreateRequest(
            @NotBlank(message = "캠페인 이름을 입력해 주세요") @Size(max = 100, message = "캠페인 이름은 100자 이하입니다")
                    String name,
            @NotNull(message = "템플릿을 골라 주세요") Long templateId,
            @Min(value = 0, message = "대조군 비율은 0~50%입니다")
                    @Max(value = 50, message = "대조군 비율은 0~50%입니다")
                    Integer controlPercent,
            @Pattern(regexp = "NAIL|LASH|WAXING|SEMI_PERMANENT|ETC", message = "시술 카테고리 값이 아닙니다")
                    String category,
            @Size(max = 3000, message = "고객은 3,000명까지 고를 수 있습니다") List<@NotNull Long> customerIds,
            @Min(value = 0, message = "방문 횟수는 0 이상입니다") Integer visitCountMin,
            @Min(value = 0, message = "방문 횟수는 0 이상입니다") Integer visitCountMax,
            PredictionStatus predictionStatus,
            LocalDate lastServiceFrom,
            LocalDate lastServiceTo) {}

    record CreateResponse(long id) {}

    @PostMapping("/api/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    CreateResponse create(@Valid @RequestBody CreateRequest r) {
        return new CreateResponse(
                campaigns.create(
                        shopSession.shopId(),
                        r.name().strip(),
                        r.templateId(),
                        r.controlPercent() == null ? 0 : r.controlPercent(),
                        new Selection(
                                null,
                                r.category(),
                                r.customerIds(),
                                r.visitCountMin(),
                                r.visitCountMax(),
                                r.predictionStatus(),
                                r.lastServiceFrom(),
                                r.lastServiceTo())));
    }

    /** 검토 체크 (FR-CMP-06). 본문 없음 */
    @PostMapping("/api/campaigns/{campaignId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void review(@PathVariable long campaignId, HttpSession session) {
        campaigns.review(
                shopSession.shopId(),
                campaignId,
                featureGuard.actorStaffId(session, FeatureKey.CAMPAIGN_SEND));
    }

    /** 동의 재확인 대상 (CONTRACTS 6장, F45). 조회라 클래스의 CAMPAIGN_SEND 대신 CAMPAIGN_VIEW */
    @RequireFeature(FeatureKey.CAMPAIGN_VIEW)
    @GetMapping("/api/consent-reconfirm-targets")
    PageResponse<ConsentReconfirmQuery.Row> reconfirmTargets(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return campaigns.reconfirmTargets(shopSession.shopId(), page, size);
    }
}

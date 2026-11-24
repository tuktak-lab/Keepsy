package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler;
import com.tuktaklab.keepsy.common.web.PageResponse;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 메시지 템플릿 목록 · 매장 기본 템플릿 (FR-CMP-03 · 13, D1, CONTRACTS 6장 「캠페인 · 발송 · 가상 휴대폰 (CMP)」) */
@Service
@RequiredArgsConstructor
public class MessageTemplateService {

    /** 매장 기본 템플릿. 문구는 CONTRACTS 6장 「매장 기본 템플릿」과 같게 둔다 (광고 표기는 발송 때 붙는다, FR-CMP-04) */
    private record Default(String name, String body, TemplatePurpose purpose) {}

    private static final List<Default> DEFAULTS =
            List.of(
                    new Default(
                            "재방문 안내",
                            "{고객명}님, {매장명}입니다. 지난 {최근시술} 시술 후 {경과일}일이 지났어요. 편하실 때 예약해 주세요.",
                            TemplatePurpose.REVISIT),
                    new Default(
                            "안부 · 혜택",
                            "{고객명}님, {매장명}입니다. 지난 {최근시술} 시술은 마음에 드셨나요? 다음 방문 때 드릴 작은 혜택을 준비해 두었어요.",
                            TemplatePurpose.CHECK_IN),
                    new Default(
                            "재방문 혜택",
                            "{고객명}님, {매장명}입니다. {최근시술} 시술 후 {경과일}일이 지났네요. 다시 찾아 주시면 드릴 혜택을 준비했어요. 예약할 때 이 문자를 보여 주세요.",
                            TemplatePurpose.WIN_BACK),
                    new Default(
                            "동의 재확인 (철회 방법 안내형)",
                            "[{매장명}] {고객명}님은 {동의일}에 {매장명}의 광고성 정보 수신에 동의하셨습니다 (야간 광고 수신: {야간광고동의}). 동의는 그대로 유지됩니다. 더 받지 않으시려면 [수신거부]를 누르거나 매장에 말씀해 주세요.",
                            TemplatePurpose.CONSENT_RECONFIRM));

    /** 2장 「2,000바이트 초과 메시지」 */
    private static final int MAX_BODY_BYTES = 2000;

    /** 변수: 중괄호로 감싼 이름 (C10) */
    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]*)\\}");

    private final MessageTemplateRepository templates;
    private final CampaignRepository campaigns;

    public record TemplateItem(
            long id,
            String name,
            String body,
            TemplatePurpose purpose,
            TemplatePurpose.MessageKind messageKind) {}

    /** {@code GET /api/message-templates}: 세션 매장 템플릿, 만든 순서 (NFR-03) */
    @Transactional(readOnly = true)
    public PageResponse<TemplateItem> list(long shopId, int page, int size) {
        return PageResponse.of(
                templates.findByShopIdOrderById(shopId, PageRequest.of(page, size)),
                t ->
                        new TemplateItem(
                                t.getId(),
                                t.getName(),
                                t.getBody(),
                                t.getPurpose(),
                                t.getPurpose().messageKind()));
    }

    /** {@code POST /api/message-templates} (FR-CMP-03, C10). @return 새 템플릿 ID */
    @Transactional
    public long create(long shopId, String name, String body, TemplatePurpose purpose) {
        validate(body, purpose);
        return templates.save(MessageTemplate.of(shopId, name, body, purpose)).getId();
    }

    /**
     * {@code PATCH /api/message-templates/{id}}: 같은 본문을 통째로 바꾼다. 작성 중 · 예약 발송 대기 캠페인이 쓰면 거부한다 (F43)
     *
     * @throws ApiException 다른 매장 · 없는 템플릿이면 {@code not_found}, 발송 전 캠페인이 쓰면 {@code conflict}
     */
    @Transactional
    public void update(
            long shopId, long templateId, String name, String body, TemplatePurpose purpose) {
        validate(body, purpose);
        // 캠페인 만들기도 이 행을 잠그므로, 만들기가 먼저 커밋되면 아래 확인이 그 캠페인을 본다
        MessageTemplate template =
                templates
                        .findForUpdateByIdAndShopId(templateId, shopId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (campaigns.existsByShopIdAndTemplateIdAndStatusIn(
                shopId, templateId, List.of(Campaign.Status.DRAFT, Campaign.Status.SCHEDULED))) {
            throw new ApiException(ErrorCode.CONFLICT, "발송 전 캠페인이 쓰는 템플릿은 고칠 수 없습니다");
        }
        template.update(name, body, purpose);
    }

    /**
     * {@code DELETE /api/message-templates/{id}}. 캠페인이 쓰는 템플릿은 지우지 않는다 (campaign.template_id FK)
     */
    @Transactional
    public void delete(long shopId, long templateId) {
        templates.delete(find(shopId, templateId));
        try {
            templates.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.CONFLICT, "캠페인에 쓴 템플릿은 지울 수 없습니다");
        }
    }

    private MessageTemplate find(long shopId, long templateId) {
        return templates
                .findById(templateId)
                .filter(t -> t.getShopId() == shopId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** 본문 2,000바이트 이하(FR-CMP-05와 같은 셈법), 용도별 허용 변수만 (C10) */
    private static void validate(String body, TemplatePurpose purpose) {
        if (MessageComposer.countBytes(body) > MAX_BODY_BYTES) {
            throw invalidBody("본문은 2,000바이트 이하로 써 주세요");
        }
        Set<String> allowed = purpose.allowedVariables();
        List<String> wrong =
                VARIABLE.matcher(body)
                        .results()
                        .map(m -> m.group(1))
                        .filter(v -> !allowed.contains(v))
                        .distinct()
                        .toList();
        if (!wrong.isEmpty()) {
            throw invalidBody(
                    "이 용도에서 쓸 수 없는 변수입니다: "
                            + wrong.stream()
                                    .map(v -> "{" + v + "}")
                                    .collect(Collectors.joining(" ")));
        }
    }

    private static ApiException invalidBody(String reason) {
        return new ApiException(
                ErrorCode.INVALID,
                ErrorCode.INVALID.defaultMessage(),
                List.of(new GlobalExceptionHandler.FieldViolation("body", reason)));
    }

    /** 매장 가입 때 기본 템플릿을 만든다 (FR-CMP-03 · 13, D1). 가입 트랜잭션 안에서 부른다 */
    @Transactional
    public void createDefaults(long shopId) {
        templates.saveAll(
                DEFAULTS.stream()
                        .map(d -> MessageTemplate.of(shopId, d.name(), d.body(), d.purpose()))
                        .toList());
    }
}

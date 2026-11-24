package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.common.web.PageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

    private final MessageTemplateRepository templates;

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

    /** 매장 가입 때 기본 템플릿을 만든다 (FR-CMP-03 · 13, D1). 가입 트랜잭션 안에서 부른다 */
    @Transactional
    public void createDefaults(long shopId) {
        templates.saveAll(
                DEFAULTS.stream()
                        .map(d -> MessageTemplate.of(shopId, d.name(), d.body(), d.purpose()))
                        .toList());
    }
}

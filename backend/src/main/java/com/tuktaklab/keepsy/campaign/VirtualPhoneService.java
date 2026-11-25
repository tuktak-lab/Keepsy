package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.port.CustomerUnsubscribe;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.common.web.PageResponse;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가상 휴대폰 (FR-CMP-16). 실제 문자는 오가지 않는다 (A6) */
@Service
@RequiredArgsConstructor
public class VirtualPhoneService {

    private final CampaignMessageRepository messages;
    private final CustomerUnsubscribe customers;

    /**
     * 가상 휴대폰 [수신거부] (FR-CMP-11 · 16, F48). 마케팅 철회 기록과 처리 안내는 고객 도메인이 한 트랜잭션에서 한다.
     *
     * @param phone 하이픈이 있든 없든 010으로 시작하는 11자리 (Controller가 검증)
     */
    @Transactional
    public void unsubscribe(long shopId, String phone) {
        customers.unsubscribe(shopId, normalize(phone));
    }

    public record InboxItem(
            long id,
            MessageKind kind,
            MessageType messageType,
            String body,
            OffsetDateTime receivedAt) {}

    /**
     * 그 번호가 받은 메시지(발송 성공 {@code SENT})만, 받은 시각 최신순 (F23). 종류와 상관없다 (명세서 5장). 대기 · 실패 · 대조군은 나오지
     * 않는다.
     *
     * @param phone 하이픈이 있든 없든 010으로 시작하는 11자리 (Controller가 검증)
     */
    @Transactional(readOnly = true)
    public PageResponse<InboxItem> inbox(long shopId, String phone, int page, int size) {
        return PageResponse.of(
                messages.findByShopIdAndRecipientPhoneAndSendStatusOrderByResultAtDescIdDesc(
                        shopId, normalize(phone), SendStatus.SENT, PageRequest.of(page, size)),
                m ->
                        new InboxItem(
                                m.getId(),
                                m.getKind(),
                                m.getMessageType(),
                                m.getBody(),
                                TimeConfig.offset(m.getResultAt())));
    }

    /** 저장 형식 010-0000-0000 (CONTRACTS 6장 고객 phone) */
    static String normalize(String phone) {
        String digits = phone.replace("-", "");
        return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
    }
}

package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.MessageSender.Outgoing;
import com.tuktaklab.keepsy.campaign.port.ShopSendSettings;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수신 거부 처리 안내 자동 발송 (FR-CMP-11, F48). 가상 휴대폰 [수신거부]와 스탭 직접 처리(철회 경로 UNSUBSCRIBE)가 같은 로직을 쓴다. 캠페인 없이
 * 나가고 광고 표기를 붙이지 않으며, 검토 체크 · 일 발송 한도를 거치지 않는다. 실제 문자는 보내지 않는다 (A6).
 */
@Service
@RequiredArgsConstructor
public class UnsubscribeNoticeService {

    /** 야간 시작 · 끝(서울) (FR-CMP-07) */
    static final LocalTime NIGHT_START = LocalTime.of(21, 0);

    static final LocalTime NIGHT_END = LocalTime.of(8, 0);

    private final MessageSender sender;
    private final ShopSendSettings shopSettings;

    /**
     * 처리 안내를 남긴다. 부르는 쪽(동의 철회) 트랜잭션에서 함께 커밋된다.
     *
     * @param recipientPhone 고객 번호 (저장 형식 010-0000-0000)
     * @param processedAt 수신 거부를 처리한 시각(서울). 야간이면 다음 08:00에 나간다
     */
    @Transactional
    public void send(
            long shopId, long customerId, String recipientPhone, LocalDateTime processedAt) {
        String body =
                "[%s] %s 수신 거부가 처리되었습니다."
                        .formatted(shopSettings.shopName(shopId), processedAt.toLocalDate());
        sender.send(
                List.of(
                        new Outgoing(
                                shopId,
                                null,
                                MessageKind.UNSUBSCRIBE_NOTICE,
                                customerId,
                                null,
                                recipientPhone,
                                MessageComposer.compose(MessageKind.UNSUBSCRIBE_NOTICE, body, null),
                                sendAfter(processedAt))));
    }

    /**
     * @return 야간(21:00~08:00)이면 다음 08:00, 낮이면 null(바로)
     */
    static LocalDateTime sendAfter(LocalDateTime at) {
        LocalTime t = at.toLocalTime();
        if (!t.isBefore(NIGHT_START)) {
            return at.toLocalDate().plusDays(1).atTime(NIGHT_END);
        }
        if (t.isBefore(NIGHT_END)) {
            return at.toLocalDate().atTime(NIGHT_END);
        }
        return null;
    }
}

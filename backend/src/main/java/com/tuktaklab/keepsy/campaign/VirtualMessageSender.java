package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.port.ShopSendSettings;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가상 발송기 (FR-CMP-10, A6). 서비스 안에서만 처리하고 <b>실제로 전송하지 않는다</b>: 외부 네트워크를 부르지 않는다. 몇 초 뒤 스케줄러가 결과를 정한다:
 * 번호 형식 오류는 실패, 매장 설정 비율만큼 무작위 실패, 나머지는 성공 (ARCHITECTURE 7장).
 */
@Component
@RequiredArgsConstructor
class VirtualMessageSender implements MessageSender {

    static final String INVALID_PHONE = "번호 형식 오류";
    static final String RANDOM_FAILURE = "가상 발송 실패";

    /** 저장 형식 010-0000-0000 (CONTRACTS 6장 고객 phone) */
    private static final Pattern PHONE = Pattern.compile("010-\\d{4}-\\d{4}");

    private final CampaignMessageRepository repository;
    private final CampaignRepository campaigns;
    private final ShopSendSettings settings;
    private final RandomGenerator virtualSendRandom;
    private final Clock clock;

    @Override
    @Transactional
    public void send(List<Outgoing> messages) {
        repository.saveAll(
                messages.stream()
                        .map(m ->
                                CampaignMessage.pending(
                                        m,
                                        m.message()
                                                .messageType()
                                                .orElseThrow(() ->
                                                        new IllegalArgumentException(
                                                                "2,000바이트를 넘는 메시지는 보낼 수 없다"))))
                        .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long cost(long shopId, int count) {
        return (long) count * settings.of(shopId).unitPrice();
    }

    /**
     * PENDING 발송 기록의 결과를 정한다 (FR-CMP-10). 서버가 재시작해도 남은 PENDING을 이어서 처리한다 (F7 복구). 행마다 PENDING일 때만
     * 바꾼다. 발송 예정 시각이 남은 행(야간 수신 거부 안내)은 그 시각이 지난 뒤에 보낸다 (F48).
     */
    @Scheduled(initialDelay = 3, fixedDelay = 3, timeUnit = TimeUnit.SECONDS)
    @Transactional
    public void deliverPending() {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, Integer> failurePercent = new HashMap<>();
        for (CampaignMessage m : repository.findDuePending(now, PageRequest.of(0, 500))) {
            int percent =
                    failurePercent.computeIfAbsent(
                            m.getShopId(), shopId -> settings.of(shopId).failurePercent());
            String reason = failureReason(m.getRecipientPhone(), percent, virtualSendRandom);
            repository.completeIfPending(
                    m.getId(), reason == null ? SendStatus.SENT : SendStatus.FAILED, reason, now);
        }
        // 대기 행이 모두 결과가 난 발송 중 캠페인은 발송 완료 (F7 「SENDING → SENT」)
        campaigns.finishSent();
    }

    /**
     * @return 실패 사유. 성공이면 null. 번호 형식 오류는 비율과 상관없이 실패 (FR-CMP-10)
     */
    static String failureReason(String phone, int failurePercent, RandomGenerator random) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            return INVALID_PHONE;
        }
        return random.nextInt(100) < failurePercent ? RANDOM_FAILURE : null;
    }
}

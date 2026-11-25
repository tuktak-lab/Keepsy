package com.tuktaklab.keepsy.campaign;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예약 발송 실행 (FR-CMP-09, F56, ARCHITECTURE 7장 「스케줄러 작업」). 30초마다 예약 시각이 지난 캠페인을 캠페인마다 한 트랜잭션에서 보낸다. 한
 * 캠페인이 실패해도 다른 캠페인은 계속 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ScheduledCampaignRunner {

    private final CampaignRepository campaigns;
    private final CampaignSendService sendService;
    private final Clock clock;

    @Scheduled(initialDelay = 30, fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void run() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (Campaign c :
                campaigns.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAt(
                        Campaign.Status.SCHEDULED, now)) {
            try {
                sendService.sendScheduled(c.getShopId(), c.getId());
            } catch (RuntimeException e) {
                // 롤백되어 SCHEDULED로 남으므로 다음 주기에 다시 시도한다
                log.error("예약 발송 실패 campaignId={}", c.getId(), e);
            }
        }
    }
}

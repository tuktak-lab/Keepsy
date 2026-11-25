package com.tuktaklab.keepsy.customer.port;

import java.time.LocalDateTime;

/** 수신 거부 처리 안내 발송 (캠페인 도메인의 일, DEVELOPMENT 4장 「도메인 연동」, FR-CMP-11, F48) */
public interface UnsubscribeNotice {

    /**
     * 처리 안내를 남긴다. 부르는 쪽 트랜잭션에서 함께 커밋되고, 야간이면 다음 08:00에 나간다.
     *
     * @param processedAt 수신 거부를 처리한 시각(서울)
     */
    void send(long shopId, long customerId, String recipientPhone, LocalDateTime processedAt);
}

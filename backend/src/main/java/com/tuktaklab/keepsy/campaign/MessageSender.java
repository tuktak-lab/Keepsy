package com.tuktaklab.keepsy.campaign;

import java.util.List;

/**
 * 모든 메시지 발송이 거치는 경계 (FR-CMP-17 구조 조건 [확정], ARCHITECTURE 7장). MVP 구현은 실제로 전송하지 않는 {@link
 * VirtualMessageSender}뿐이다 (A6). 실제 발송으로 바꿀 때 이 구현만 바꾸고 부르는 쪽(즉시 · 예약 발송, 수신 거부 안내, 동의 재확인 안내)은 그대로
 * 둔다. 구현이 하나인 인터페이스지만 교체 계획이 명세서에 있어 둔다 (DEVELOPMENT 4장).
 */
public interface MessageSender {

    /**
     * 보낼 메시지 하나.
     *
     * @param campaignId 수신 거부 처리 안내면 null (FR-CMP-11)
     * @param category 캠페인 카테고리. 동의 재확인 · 수신 거부 안내면 null (E7)
     * @param message 광고 표기까지 붙은 최종 본문. 2,000바이트 이하여야 한다 (FR-CMP-05)
     */
    record Outgoing(
            long shopId,
            Long campaignId,
            MessageKind kind,
            long customerId,
            String category,
            String recipientPhone,
            ComposedMessage message) {}

    /**
     * 고객별 발송 기록을 {@code PENDING}으로 남긴다. 결과(SENT · FAILED)는 구현이 나중에 채운다 (FR-CMP-10). 부르는 쪽 트랜잭션에서 함께
     * 커밋된다 (F7: 상태 전이와 발송 기록 생성을 한 트랜잭션으로).
     *
     * @throws IllegalArgumentException 2,000바이트를 넘는 메시지 (부르는 쪽이 미리 걸러야 한다)
     */
    void send(List<Outgoing> messages);

    /** 메시지 {@code count}건의 비용(원). MVP는 매장의 가상 단가(기본 0원)로 계산한다 (FR-CMP-05, A6) */
    long cost(long shopId, int count);
}

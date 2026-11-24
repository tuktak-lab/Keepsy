package com.tuktaklab.keepsy.campaign;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 캠페인 대상 자동 제외 사유 (FR-CMP-02, B2, FR-CMP-13). 값 이름과 우선순위는 CONTRACTS 3장 「캠페인 제외 사유」를 따른다. 고객당 사유 하나라
 * 사유별 건수의 합이 제외 인원과 같다. 삭제된 고객은 예측 행이 없어 처음부터 대상에 나오지 않으므로 사유가 없다 (FR-CUS-10).
 */
public enum ExclusionReason {
    /** 수신 거부: 마케팅 동의 최신 행이 철회, 철회 경로 {@code UNSUBSCRIBE} */
    UNSUBSCRIBED,
    /** 미동의: 마케팅 동의 행이 없거나 미동의(직접 철회 포함) */
    NO_CONSENT,
    /** 재발송 제한 기간 안에 발송 성공한 광고 메시지가 있음 */
    RESEND_LIMIT;

    /**
     * @param kind 이 캠페인이 보낼 메시지 종류. 재발송 제한은 광고에만 적용하고 동의 재확인 안내에는 적용하지 않는다 (FR-CMP-13)
     * @param marketingConsent 마케팅 동의 최신 행이 동의인지
     * @param unsubscribed 최신 행의 철회 경로가 {@code UNSUBSCRIBE}인지
     * @param lastAdSentAt 마지막으로 발송 성공한 광고 메시지의 결과 시각 (실패 · 대조군 제외, 명세서 5장 campaign_message)
     * @param resendLimitDays 재발송 제한 기간 (매장 설정, 기본 7일)
     * @param now 지금 (Asia/Seoul)
     * @return 제외 사유. 보낼 수 있으면 비어 있다
     */
    public static Optional<ExclusionReason> of(
            MessageKind kind,
            boolean marketingConsent,
            boolean unsubscribed,
            Optional<LocalDateTime> lastAdSentAt,
            int resendLimitDays,
            LocalDateTime now) {
        if (!marketingConsent) {
            return Optional.of(unsubscribed ? UNSUBSCRIBED : NO_CONSENT);
        }
        // 최근 N일 = 지금부터 N × 24시간 전보다 뒤 (B2)
        LocalDateTime limitStart = now.minusDays(resendLimitDays);
        if (kind == MessageKind.AD && lastAdSentAt.filter(t -> t.isAfter(limitStart)).isPresent()) {
            return Optional.of(RESEND_LIMIT);
        }
        return Optional.empty();
    }
}

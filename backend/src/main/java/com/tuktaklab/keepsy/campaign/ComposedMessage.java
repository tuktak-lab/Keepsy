package com.tuktaklab.keepsy.campaign;

import java.util.Optional;

/**
 * 발송할 최종 본문과 바이트 수 (FR-CMP-05 미리보기에 그대로 보여 준다).
 *
 * @param text 광고 표기까지 붙은 최종 본문 (줄바꿈은 \n)
 * @param bytes 최종 본문의 바이트 수
 */
public record ComposedMessage(String text, int bytes) {

    static final int SMS_MAX_BYTES = 90;
    static final int LMS_MAX_BYTES = 2_000;

    /** 90바이트 이하 SMS, 2,000바이트 이하 LMS. 넘으면 비어 있다 (발송 불가) */
    public Optional<MessageType> messageType() {
        if (bytes <= SMS_MAX_BYTES) {
            return Optional.of(MessageType.SMS);
        }
        if (bytes <= LMS_MAX_BYTES) {
            return Optional.of(MessageType.LMS);
        }
        return Optional.empty();
    }

    public boolean sendable() {
        return messageType().isPresent();
    }
}

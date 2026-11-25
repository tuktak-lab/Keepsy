package com.tuktaklab.keepsy.account;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 개발용 메일 발송기 (F49). 가상 메일함 표에만 쌓고 외부로 보내지 않는다. 본문에 재설정 링크가 있어 로그에도 남기지 않는다 */
@Component
@RequiredArgsConstructor
class VirtualMailSender implements MailSender {

    private final VirtualMailRepository mails;
    private final Clock clock;

    @Override
    public void send(long shopId, String recipientEmail, String subject, String body) {
        mails.save(VirtualMail.of(shopId, recipientEmail, subject, body, LocalDateTime.now(clock)));
    }
}

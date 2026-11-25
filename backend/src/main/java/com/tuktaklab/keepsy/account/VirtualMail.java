package com.tuktaklab.keepsy.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 가상 메일함의 메일 하나 (명세서 5장 virtual_mail, F49). 실제 메일은 나가지 않는다 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VirtualMail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private String recipientEmail;

    private String subject;

    private String body;

    private LocalDateTime sentAt;

    static VirtualMail of(
            long shopId, String recipientEmail, String subject, String body, LocalDateTime sentAt) {
        VirtualMail m = new VirtualMail();
        m.shopId = shopId;
        m.recipientEmail = recipientEmail;
        m.subject = subject;
        m.body = body;
        m.sentAt = sentAt;
        return m;
    }
}

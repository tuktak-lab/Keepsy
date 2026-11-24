package com.tuktaklab.keepsy.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 메시지 템플릿 (명세서 5장 message_template, FR-CMP-03). 메시지 종류는 저장하지 않고 용도로 정한다 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private String name;

    /** 치환 전 본문. 변수는 C10 */
    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    private TemplatePurpose purpose;

    static MessageTemplate of(long shopId, String name, String body, TemplatePurpose purpose) {
        MessageTemplate t = new MessageTemplate();
        t.shopId = shopId;
        t.name = name;
        t.body = body;
        t.purpose = purpose;
        return t;
    }
}

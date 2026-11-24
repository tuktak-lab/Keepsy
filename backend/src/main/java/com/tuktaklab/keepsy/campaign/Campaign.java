package com.tuktaklab.keepsy.campaign;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 캠페인 (명세서 5장 campaign). 지금은 만들기(#89)가 쓰는 칸만 매핑한다. 검토 · 예약 · 발송 · 취소 칸은 그 기능에서 더한다. 상태 전이는 조건부
 * UPDATE로 한다 (ARCHITECTURE 7장, F7).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign {

    /** 캠페인 상태 (CONTRACTS 3장, F7) */
    public enum Status {
        DRAFT,
        SCHEDULED,
        SENDING,
        SENT,
        CANCELED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private String name;

    /** 동의 재확인 안내면 null (E7) */
    private String category;

    private Long templateId;

    @Enumerated(EnumType.STRING)
    private TemplatePurpose.MessageKind messageKind;

    private int controlPercent;

    @Enumerated(EnumType.STRING)
    private Status status;

    static Campaign draft(
            long shopId,
            String name,
            String category,
            MessageTemplate template,
            int controlPercent) {
        Campaign c = new Campaign();
        c.shopId = shopId;
        c.name = name;
        c.category = category;
        c.templateId = template.getId();
        c.messageKind = template.getPurpose().messageKind();
        c.controlPercent = controlPercent;
        c.status = Status.DRAFT;
        return c;
    }
}

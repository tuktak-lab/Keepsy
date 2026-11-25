package com.tuktaklab.keepsy.campaign;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고객별 발송 기록 (명세서 5장 campaign_message, FR-CMP-10). 발송은 {@link MessageSender}만 만들고, 결과(SENT ·
 * FAILED)는 발송기가 조건부 UPDATE로 채운다 (ARCHITECTURE 7장, F7). 대조군 행은 캠페인 만들기(#89)에서 더한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CampaignMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    /** 수신 거부 처리 안내면 null (FR-CMP-11) */
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    private MessageKind kind;

    private Long customerId;

    /** 캠페인 카테고리 NAIL | LASH | WAXING | SEMI_PERMANENT | ETC. 동의 재확인 · 수신 거부 안내면 null (E7) */
    private String category;

    private boolean controlGroup;

    /** 고객 삭제 시 null (C12) */
    private String recipientPhone;

    @Enumerated(EnumType.STRING)
    private MessageType messageType;

    /** 치환된 본문. 고객 삭제 시 null (C12) */
    private String body;

    @Enumerated(EnumType.STRING)
    private SendStatus sendStatus;

    private String failureReason;

    /** 발송 결과 시각. SENT면 발송 성공 시각 (귀속 · 재발송 제한 기준) */
    private LocalDateTime resultAt;

    /** 이 시각 이후 발송. null이면 바로. 야간에 접수된 수신 거부 처리 안내만 쓴다 (FR-CMP-07 · 11, F48) */
    private LocalDateTime sendAfter;

    static CampaignMessage pending(MessageSender.Outgoing m, MessageType type) {
        CampaignMessage row = new CampaignMessage();
        row.shopId = m.shopId();
        row.campaignId = m.campaignId();
        row.kind = m.kind();
        row.customerId = m.customerId();
        row.category = m.category();
        row.recipientPhone = m.recipientPhone();
        row.messageType = type;
        row.body = m.message().text();
        row.sendStatus = SendStatus.PENDING;
        row.sendAfter = m.sendAfter();
        return row;
    }

    /** 대조군 행: 발송하지 않고 비교용으로만 남긴다. 발송 상태 · 메시지 유형 · 본문이 없다 (FR-CMP-18) */
    static CampaignMessage control(
            long shopId, long campaignId, MessageKind kind, long customerId, String category) {
        CampaignMessage row = new CampaignMessage();
        row.shopId = shopId;
        row.campaignId = campaignId;
        row.kind = kind;
        row.customerId = customerId;
        row.category = category;
        row.controlGroup = true;
        return row;
    }

    /** 발송 시점 재확인으로 빠진 고객: 보내지 않고 실패와 사유로 남긴다 (F51) */
    static CampaignMessage skipped(
            long shopId,
            long campaignId,
            MessageKind kind,
            long customerId,
            String category,
            String recipientPhone,
            String reason,
            LocalDateTime now) {
        CampaignMessage row = new CampaignMessage();
        row.shopId = shopId;
        row.campaignId = campaignId;
        row.kind = kind;
        row.customerId = customerId;
        row.category = category;
        row.recipientPhone = recipientPhone;
        row.sendStatus = SendStatus.FAILED;
        row.failureReason = reason;
        row.resultAt = now;
        return row;
    }
}

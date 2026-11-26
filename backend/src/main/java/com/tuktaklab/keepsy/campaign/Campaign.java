package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
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

/** 캠페인 (명세서 5장 campaign). 상태 전이는 조건부 UPDATE로 한다 (ARCHITECTURE 7장, F7). */
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

    /** 캠페인 취소 사유 (CONTRACTS 3장, F7 · F56 · F67) */
    public enum CancelReason {
        USER_CANCELED,
        DAILY_LIMIT_EXCEEDED,
        SHOP_INFO_MISSING,
        /** 동의 재확인 안내 캠페인의 예약 발송 처리 시각이 야간 (F67) */
        NIGHT_TIME
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

    /** 검토 체크한 스탭 (FR-CMP-06) */
    private Long reviewedByStaffId;

    private LocalDateTime reviewedAt;

    /** 발송 시작 시각. 즉시 발송 · 예약 시각 도래 때 조건부 UPDATE로 채운다 (F7) */
    private LocalDateTime sentAt;

    /** 예약 발송 시각 (FR-CMP-09, F56) */
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    private CancelReason cancelReason;

    private LocalDateTime canceledAt;

    /**
     * 즉시 발송 · 예약 발송 전에 부른다 (FR-CMP-06, CONTRACTS 1장). 동의 재확인 안내 캠페인도 같다 (FR-CMP-13)
     *
     * @throws ApiException 검토 기록이 없으면 {@code review_required}
     */
    public void requireReviewed() {
        if (reviewedByStaffId == null || reviewedAt == null) {
            throw new ApiException(ErrorCode.REVIEW_REQUIRED);
        }
    }

    /**
     * 발송 · 예약 전에 상태를 먼저 본다. 다른 검사(야간 등)보다 앞에 두어 이미 보낸 캠페인에는 {@code conflict}가 나가게 한다. 동시 요청은 조건부
     * UPDATE가 막는다 (F7)
     *
     * @throws ApiException 작성 중 · 예약 발송 대기가 아니면 {@code conflict}
     */
    public void requireNotStarted() {
        if (status != Status.DRAFT && status != Status.SCHEDULED) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 발송했거나 취소된 캠페인입니다");
        }
    }

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

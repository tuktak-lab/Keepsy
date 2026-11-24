package com.tuktaklab.keepsy.customer;

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

/** 동의 이력 한 행 (명세서 5장 consent, E8). 고치지 않고 쌓으며 항목별 최신 행이 현재 상태다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Consent {

    /** CONTRACTS 3장 「동의 항목」 */
    public enum Item {
        PRIVACY,
        PHOTO,
        MARKETING,
        NIGHT_AD,
        HEALTH
    }

    /** CONTRACTS 3장 「동의 방법」 */
    public enum Method {
        TABLET,
        PAPER,
        VERBAL,
        IMPORT
    }

    /** CONTRACTS 3장 「철회 경로」. 캠페인 제외 사유의 "수신 거부"와 "미동의"를 가른다 (FR-CMP-02) */
    public enum WithdrawalPath {
        UNSUBSCRIBE,
        DIRECT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private Long customerId;

    @Enumerated(EnumType.STRING)
    private Item item;

    private boolean agreed;

    private LocalDateTime recordedAt;

    /** 처리 스탭 = 현재 근무자 */
    private Long staffId;

    @Enumerated(EnumType.STRING)
    private Method method;

    /** 철회 행에만 있다 */
    @Enumerated(EnumType.STRING)
    private WithdrawalPath withdrawalPath;

    /** 정정 행이면 고친 이력 행 ID (F44) */
    private Long correctsConsentId;

    /**
     * 정정 행 (F44). 일시 · 동의 방법 · 철회 경로는 효력 기준 값을 받는다(처리 시각이 아님).
     *
     * @param staffId 처리 스탭
     */
    static Consent correction(
            Consent target,
            boolean agreed,
            Method method,
            WithdrawalPath withdrawalPath,
            LocalDateTime effectiveAt,
            long staffId) {
        Consent consent = new Consent();
        consent.shopId = target.shopId;
        consent.customerId = target.customerId;
        consent.item = target.item;
        consent.agreed = agreed;
        consent.recordedAt = effectiveAt;
        consent.staffId = staffId;
        consent.method = method;
        consent.withdrawalPath = withdrawalPath;
        consent.correctsConsentId = target.id;
        return consent;
    }

    static Consent agreed(
            Customer customer, Item item, Method method, long staffId, LocalDateTime now) {
        return of(customer, item, true, method, null, staffId, now);
    }

    /**
     * @param withdrawalPath 철회일 때만, 동의면 null
     */
    static Consent of(
            Customer customer,
            Item item,
            boolean agreed,
            Method method,
            WithdrawalPath withdrawalPath,
            long staffId,
            LocalDateTime now) {
        Consent consent = new Consent();
        consent.shopId = customer.getShopId();
        consent.customerId = customer.getId();
        consent.item = item;
        consent.agreed = agreed;
        consent.recordedAt = now;
        consent.staffId = staffId;
        consent.method = method;
        consent.withdrawalPath = withdrawalPath;
        return consent;
    }
}

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

/** 동의 이력 한 행 (명세서 5장 consent, E8). 고치지 않고 쌓으며 항목별 최신 행이 현재 상태다. 철회 경로는 동의 철회(#48)를 구현할 때 매핑한다. */
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

    static Consent agreed(
            Customer customer, Item item, Method method, long staffId, LocalDateTime now) {
        Consent consent = new Consent();
        consent.shopId = customer.getShopId();
        consent.customerId = customer.getId();
        consent.item = item;
        consent.agreed = true;
        consent.recordedAt = now;
        consent.staffId = staffId;
        consent.method = method;
        return consent;
    }
}

package com.tuktaklab.keepsy.common.security;

/**
 * 기능 키: 공용/PIN 설정 단위 (CONTRACTS 3장 [제안], 명세서 2장 표의 한 행이 키 하나). 기본값은 V1 {@code
 * shop.pin_locked_features} DEFAULT, 토글은 FR-ACC-05.
 */
public enum FeatureKey {
    DASHBOARD_SEARCH(Access.PUBLIC),
    RESERVATION(null),
    INSIGHT(null),
    CUSTOMER_VIEW(null),
    CUSTOMER_WRITE(null),
    CONSENT(Access.PUBLIC),
    CONTACT_FULL(null),
    CUSTOMER_EDIT(null),
    SALES_ANALYTICS(null),
    PAYMENT_EDIT(null),
    CAMPAIGN_VIEW(null),
    CAMPAIGN_SEND(null),
    OWNER_PIN(Access.OWNER_PIN);

    /** 기능을 쓰는 데 필요한 것 */
    public enum Access {
        PUBLIC,
        PIN,
        OWNER_PIN
    }

    private final Access fixed;

    FeatureKey(Access fixed) {
        this.fixed = fixed;
    }

    /** 고정 항목이면 그 값, 토글 가능하면 null (명세서 2장 「토글」 열) */
    public Access fixed() {
        return fixed;
    }
}

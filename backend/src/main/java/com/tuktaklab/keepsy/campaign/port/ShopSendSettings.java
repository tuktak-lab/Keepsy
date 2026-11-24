package com.tuktaklab.keepsy.campaign.port;

/** 발송에 필요한 매장 설정값 (다른 도메인의 값, DEVELOPMENT 4장 「도메인 연동」). 이름과 반환 타입은 캠페인 도메인이 필요한 모양이다. */
public interface ShopSendSettings {

    /**
     * @param failurePercent 가상 발송 무작위 실패 비율 % (FR-CMP-10, 기본 0 F17)
     * @param unitPrice 가상 단가(원) (FR-CMP-05, 기본 0)
     */
    record Settings(int failurePercent, int unitPrice) {}

    Settings of(long shopId);
}

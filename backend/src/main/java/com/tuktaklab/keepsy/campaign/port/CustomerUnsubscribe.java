package com.tuktaklab.keepsy.campaign.port;

/** 수신 거부를 마케팅 철회로 기록한다 (고객 도메인의 일, DEVELOPMENT 4장 「도메인 연동」, FR-CMP-11, F48) */
public interface CustomerUnsubscribe {

    /**
     * 그 번호의 고객을 수신 거부로 철회하고 처리 안내를 남긴다. 이미 수신 거부 상태면 아무것도 하지 않는다.
     *
     * @param phone 저장 형식 010-0000-0000
     * @throws com.tuktaklab.keepsy.common.error.ApiException 그 번호의 활성 고객이 없으면 {@code not_found}
     */
    void unsubscribe(long shopId, String phone);
}

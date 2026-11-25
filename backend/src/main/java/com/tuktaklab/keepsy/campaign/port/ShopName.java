package com.tuktaklab.keepsy.campaign.port;

/** 매장명 (다른 도메인의 값, DEVELOPMENT 4장 「도메인 연동」). 수신 거부 처리 안내의 전송자 명칭에 쓴다 (FR-CMP-11, F48) */
public interface ShopName {

    String of(long shopId);
}

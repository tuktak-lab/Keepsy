package com.tuktaklab.keepsy.account.port;

/** 매장 가입 때 다른 도메인이 만들어 둘 매장 기본 데이터 (DEVELOPMENT 4장 「도메인 연동」) */
public interface ShopDefaults {

    /** 기본 메시지 템플릿 (FR-CMP-03 · 13, D1). 가입 트랜잭션 안에서 부른다 */
    void create(long shopId);
}

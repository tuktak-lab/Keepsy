package com.tuktaklab.keepsy.campaign;

/** 일 발송 한도 판정 (FR-CMP-12, B1, 결정 F54). 값만으로 정해지는 규칙이라 따로 두고 단위 테스트한다 (ARCHITECTURE 4장) */
public final class DailyLimitPolicy {

    private DailyLimitPolicy() {}

    /**
     * 남은 건수보다 보낼 고객이 많으면 전체를 거부한다(일부만 보내지 않음, F7). 보낼 고객이 없으면 막지 않는다.
     *
     * @param sentToday 오늘(서울) 이미 발송기에 넘긴 건수
     * @param sending 이번에 실제로 보낼 고객 수
     * @param limit 매장 일 발송 한도
     */
    public static boolean allows(long sentToday, int sending, int limit) {
        return sending == 0 || sentToday + sending <= limit;
    }
}

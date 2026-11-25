package com.tuktaklab.keepsy.campaign;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 야간 발송 제한 (FR-CMP-07, C11, NFR-12, 결정 F52). 값만으로 정해지는 규칙이라 따로 두고 단위 테스트한다 (ARCHITECTURE 4장). 즉시 발송
 * · 예약 발송(#97) · 수신 거부 안내(F48)가 같은 기준을 쓴다. 시각은 모두 서울 시각이다.
 */
public final class NightSendPolicy {

    /** 야간 시작. 이 시각부터 야간 (F52) */
    public static final LocalTime NIGHT_START = LocalTime.of(21, 0);

    /** 야간 끝. 이 시각부터 낮 (F52) */
    public static final LocalTime NIGHT_END = LocalTime.of(8, 0);

    private NightSendPolicy() {}

    /** 21:00:00부터 08:00:00 전까지 (F52) */
    public static boolean isNight(LocalDateTime at) {
        LocalTime t = at.toLocalTime();
        return !t.isBefore(NIGHT_START) || t.isBefore(NIGHT_END);
    }

    /**
     * 이 시각에 캠페인을 보낼 수 있는지 (FR-CMP-07). 낮이면 언제나, 야간이면 광고 캠페인이고 실제로 보낼 고객 전원이 야간 광고 동의일 때만 (C11,
     * F52). 동의 재확인 안내 캠페인은 야간 예외가 없다 (FR-CMP-13).
     *
     * @param allNightAdConsent 실제로 보낼 고객(대조군 · 발송 시점에 빠지는 고객 제외) 전원이 야간 광고 동의인지. 보낼 고객이 없으면 true
     */
    public static boolean allows(LocalDateTime at, boolean ad, boolean allNightAdConsent) {
        return !isNight(at) || (ad && allNightAdConsent);
    }

    /** 야간이면 다음 낮 시작(08:00), 낮이면 null(바로). 수신 거부 처리 안내가 쓴다 (FR-CMP-11, F48) */
    public static LocalDateTime nextDaytime(LocalDateTime at) {
        if (!isNight(at)) {
            return null;
        }
        return at.toLocalTime().isBefore(NIGHT_END)
                ? at.toLocalDate().atTime(NIGHT_END)
                : at.toLocalDate().plusDays(1).atTime(NIGHT_END);
    }
}

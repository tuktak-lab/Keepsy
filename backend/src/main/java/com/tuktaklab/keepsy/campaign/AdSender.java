package com.tuktaklab.keepsy.campaign;

/**
 * 광고 표기에 들어가는 매장 정보 (FR-SET-01의 매장명 · 전화번호 · 수신 거부 번호).
 * 매장 정보는 settings 도메인에 있으므로, 저장 · 발송 단계에서 Port가 이 record로 바꿔 넘긴다.
 */
public record AdSender(String shopName, String shopPhone, String unsubscribeNumber) {

    public AdSender {
        requireText(shopName, "매장명");
        requireText(shopPhone, "매장 전화번호");
        requireText(unsubscribeNumber, "수신 거부 번호");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("광고 표기에 필요한 " + name + "이(가) 비어 있다");
        }
    }
}

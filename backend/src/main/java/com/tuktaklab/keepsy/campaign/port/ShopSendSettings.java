package com.tuktaklab.keepsy.campaign.port;

import com.tuktaklab.keepsy.campaign.AdSender;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;

/** 발송에 필요한 매장 설정값 (다른 도메인의 값, DEVELOPMENT 4장 「도메인 연동」). 이름과 반환 타입은 캠페인 도메인이 필요한 모양이다. */
public interface ShopSendSettings {

    /**
     * @param failurePercent 가상 발송 무작위 실패 비율 % (FR-CMP-10, 기본 0 F17)
     * @param unitPrice 가상 단가(원) (FR-CMP-05, 기본 0)
     */
    record Settings(int failurePercent, int unitPrice) {}

    /**
     * 메시지에 들어가는 매장 정보 (FR-SET-01). 번호는 비워 저장할 수 있어 null일 수 있다 (F39).
     *
     * @param name {매장명} 치환과 광고 표기에 쓴다 (F46, D5)
     */
    record ShopContact(String name, String phone, String unsubscribeNumber) {

        /**
         * 광고 표기에 쓸 매장 정보 (FR-CMP-04, D5). 미리보기 · 즉시 발송 · 예약 발송 등록 · 예약 시각 발송이 같이 쓴다 (F47).
         *
         * @throws ApiException 전화번호 · 수신 거부 번호 중 하나라도 비었으면 {@code conflict}. 스케줄러에서는 HTTP 응답이 아니라
         *     기존 상태 전이 정책대로 처리한다
         */
        public AdSender adSender() {
            if (isBlank(phone) || isBlank(unsubscribeNumber)) {
                throw new ApiException(
                        ErrorCode.CONFLICT, "광고 표기에 필요한 매장 전화번호와 수신 거부 번호를 매장 정보에 먼저 등록해 주세요.");
            }
            return new AdSender(name, phone, unsubscribeNumber);
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }

    Settings of(long shopId);

    ShopContact contact(long shopId);
}

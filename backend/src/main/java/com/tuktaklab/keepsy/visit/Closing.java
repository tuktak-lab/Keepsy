package com.tuktaklab.keepsy.visit;

import java.util.List;

/**
 * 시술 마감 카드 저장 입력 (FR-PAY-04, E4). 사진과 동의 받기는 각자의 API로 따로 저장한다 (F9).
 *
 * @param services 확인한 실제 시술. null이면 예정 시술을 예약 담당 스탭으로 채운다. 완료된 예약이면 null이어야 한다 (읽기 전용, E4)
 * @param noCustomer 고객 없는 예약을 손님이 동의를 거부해 "고객 없음"으로 마감 (E3). 메모 · 주기 계산 제외는 받지 않는다
 */
public record Closing(
        List<Item> services, int amount, PaymentMethod method, String memo, boolean noCustomer) {

    /** 실제 시술 한 건: 메뉴, 시술 담당 스탭, 이번 시술 주기 계산 제외 (FR-VIS-05) */
    public record Item(long serviceMenuId, long staffId, boolean cycleExcluded) {}
}

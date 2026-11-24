package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.security.ShopScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 설정값 (FR-SET-04, F21: shop 테이블은 account가 맡는다). 다른 도메인은 이 공개 메서드로 읽는다 (DEVELOPMENT 4장 「도메인 연동」).
 * 설정 화면의 조회 · 수정(#33)도 여기에 더한다.
 */
@Service
@RequiredArgsConstructor
public class ShopSettingsService {

    private final ShopRepository shopRepository;

    /**
     * @param virtualFailurePercent 가상 발송 무작위 실패 비율 % (FR-CMP-10)
     * @param virtualUnitPrice 가상 단가(원) (FR-CMP-05)
     */
    public record SendSettings(int virtualFailurePercent, int virtualUnitPrice) {}

    /**
     * @throws com.tuktaklab.keepsy.common.error.ApiException 매장이 없으면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public SendSettings sendSettings(long shopId) {
        Shop shop = ShopScope.orNotFound(shopRepository.findById(shopId));
        return new SendSettings(shop.getVirtualFailurePercent(), shop.getVirtualUnitPrice());
    }
}

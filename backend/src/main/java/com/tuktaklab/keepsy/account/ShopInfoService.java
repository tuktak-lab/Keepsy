package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.Shop.OpeningHours;
import com.tuktaklab.keepsy.common.security.ShopScope;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 정보 · 영업시간 조회 (FR-SET-01). 매장 설정 화면과 예약 화면이 쓰고, 다른 도메인도 매장 정보가 필요하면 이 공개 메서드를 부른다 (DEVELOPMENT
 * 4장 「도메인 연동」).
 */
@Service
@RequiredArgsConstructor
public class ShopInfoService {

    private final ShopRepository shopRepository;

    public record ShopInfo(
            String name,
            String phone,
            String senderNumber,
            String unsubscribeNumber,
            List<OpeningHours> openingHours) {}

    /**
     * @throws com.tuktaklab.keepsy.common.error.ApiException 매장이 없으면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public ShopInfo get(long shopId) {
        Shop shop = ShopScope.orNotFound(shopRepository.findById(shopId));
        return new ShopInfo(
                shop.getName(),
                shop.getPhone(),
                shop.getSenderNumber(),
                shop.getUnsubscribeNumber(),
                shop.openingHours());
    }
}

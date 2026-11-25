package com.tuktaklab.keepsy.campaign.port;

import com.tuktaklab.keepsy.account.ShopInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 매장명은 account의 공개 Service로 읽는다 (F21) */
@Component
@RequiredArgsConstructor
class AccountShopName implements ShopName {

    private final ShopInfoService shopInfo;

    @Override
    public String of(long shopId) {
        return shopInfo.get(shopId).name();
    }
}

package com.tuktaklab.keepsy.campaign.port;

import com.tuktaklab.keepsy.account.ShopInfoService;
import com.tuktaklab.keepsy.account.ShopInfoService.ShopInfo;
import com.tuktaklab.keepsy.account.ShopSettingsService;
import com.tuktaklab.keepsy.account.ShopSettingsService.SendSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 매장 설정값 · 매장 정보는 account의 공개 Service로 읽는다 (F21) */
@Component
@RequiredArgsConstructor
class AccountShopSendSettings implements ShopSendSettings {

    private final ShopSettingsService shopSettings;
    private final ShopInfoService shopInfo;

    @Override
    public Settings of(long shopId) {
        SendSettings s = shopSettings.sendSettings(shopId);
        return new Settings(s.virtualFailurePercent(), s.virtualUnitPrice());
    }

    @Override
    public ShopContact contact(long shopId) {
        ShopInfo s = shopInfo.get(shopId);
        return new ShopContact(s.name(), s.phone(), s.unsubscribeNumber());
    }
}

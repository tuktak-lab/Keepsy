package com.tuktaklab.keepsy.account.port;

import com.tuktaklab.keepsy.campaign.MessageTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** campaign 도메인이 공개한 {@link MessageTemplateService}로 기본 템플릿을 만든다 */
@Component
@RequiredArgsConstructor
class ShopDefaultsAdapter implements ShopDefaults {

    private final MessageTemplateService templates;

    @Override
    public void create(long shopId) {
        templates.createDefaults(shopId);
    }
}

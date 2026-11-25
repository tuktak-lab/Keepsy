package com.tuktaklab.keepsy.campaign.port;

import com.tuktaklab.keepsy.customer.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** customer 도메인이 공개한 {@link CustomerService}로 기록한다 */
@Component
@RequiredArgsConstructor
class CustomerServiceUnsubscribe implements CustomerUnsubscribe {

    private final CustomerService customers;

    @Override
    public void unsubscribe(long shopId, String phone) {
        customers.unsubscribeByPhone(shopId, phone);
    }
}

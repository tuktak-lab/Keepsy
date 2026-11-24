package com.tuktaklab.keepsy.customer;

import java.util.Set;

/**
 * 고객 등록 입력 (CONTRACTS 6장 「고객 (CUS)」 POST /api/customers). 형식 검증은 Controller, 업무 규칙은 {@link
 * CustomerService}
 */
public record NewCustomer(
        String name,
        String phone,
        Set<Consent.Item> consents,
        Consent.Method consentMethod,
        Customer.Gender gender,
        Integer birthYear,
        Customer.PreferredLanguage preferredLanguage,
        Customer.AcquisitionChannel acquisitionChannel) {}

package com.tuktaklab.keepsy.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 API에 필요한 기능 키 (CONTRACTS 4장 「권한」 열). 현재 근무자가 있어야 하고, 기능 설정이 PIN · 원장 PIN이면 PIN 인증이 있어야 한다. 근무자만
 * 있으면 되는 API는 {@link RequireWorker}, 매장 세션만 있으면 되는 API는 아무것도 붙이지 않는다.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireFeature {
    FeatureKey value();
}

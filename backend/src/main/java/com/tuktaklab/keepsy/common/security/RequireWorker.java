package com.tuktaklab.keepsy.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 현재 근무자만 있으면 되는 API (CONTRACTS 4장 「권한」 열 「근무자」, FR-ACC-10) */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireWorker {}

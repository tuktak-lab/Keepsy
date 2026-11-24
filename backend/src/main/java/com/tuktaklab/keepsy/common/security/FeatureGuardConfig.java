package com.tuktaklab.keepsy.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link FeatureGuard}를 {@code /api/**} 앞에 건다. {@code @WebMvcTest}는 이 설정을 읽지만 FeatureGuard(DB 필요)는
 * 읽지 않으므로, 그때는 권한 검사 없이 돈다. 권한까지 테스트하려면 {@code @Import(FeatureGuard.class)}와 {@code @MockitoBean
 * FeatureSettingsReader}를 쓴다.
 */
@Configuration
@RequiredArgsConstructor
class FeatureGuardConfig implements WebMvcConfigurer {

    private final ObjectProvider<FeatureGuard> guard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        FeatureGuard featureGuard = guard.getIfAvailable();
        if (featureGuard == null) {
            return;
        }
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler) {
                                if (handler instanceof HandlerMethod method) {
                                    featureGuard.check(request.getSession(false), method);
                                }
                                return true;
                            }
                        })
                .addPathPatterns("/api/**");
    }
}

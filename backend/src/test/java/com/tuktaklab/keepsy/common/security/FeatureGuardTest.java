package com.tuktaklab.keepsy.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 기능별 공용/PIN 검사 (Keepsy #4 「작업 항목」 테스트, DEVELOPMENT 7장 단위 테스트 우선 대상). DB 없이 돈다. */
class FeatureGuardTest {

    private static final long SHOP = 1L;
    private static final long WORKER = 10L;
    private static final long PIN_STAFF = 20L;
    private static final long OWNER = 30L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);

    /** 명세서 2장 기본값: PIN 6개 */
    private static final EnumSet<FeatureKey> DEFAULT_LOCKED =
            EnumSet.of(
                    FeatureKey.CONTACT_FULL,
                    FeatureKey.CUSTOMER_EDIT,
                    FeatureKey.SALES_ANALYTICS,
                    FeatureKey.PAYMENT_EDIT,
                    FeatureKey.CAMPAIGN_VIEW,
                    FeatureKey.CAMPAIGN_SEND);

    private final ShopSession shopSession = new ShopSession();
    private final FeatureSettingsReader settings = mock(FeatureSettingsReader.class);
    private final FeatureGuard guard =
            new FeatureGuard(
                    shopSession,
                    settings,
                    Clock.fixed(NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE));
    private final MockHttpSession session = new MockHttpSession();

    @BeforeEach
    void signedInWithWorker() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(SHOP, null, List.of()));
        shopSession.setCurrentStaffId(session, WORKER);
        when(settings.lockedFeatures(SHOP)).thenReturn(DEFAULT_LOCKED);
        when(settings.isOwner(SHOP, OWNER)).thenReturn(true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void pin(long staffId, LocalDateTime expiresAt) {
        shopSession.setPin(session, staffId, expiresAt);
    }

    private ErrorCode errorOf(FeatureKey feature) {
        try {
            guard.check(session, feature);
            return null;
        } catch (ApiException e) {
            return e.errorCode();
        }
    }

    @Test
    void 현재_근무자가_없으면_worker_required() {
        shopSession.setCurrentStaffId(session, null);

        assertThat(errorOf(null)).isEqualTo(ErrorCode.WORKER_REQUIRED);
        assertThat(errorOf(FeatureKey.RESERVATION)).isEqualTo(ErrorCode.WORKER_REQUIRED);
    }

    @Test
    void 비활성화된_근무자가_세션에_남아_있으면_다음_요청부터_worker_required() {
        when(settings.isDeactivated(SHOP, WORKER)).thenReturn(true);

        assertThat(errorOf(null)).isEqualTo(ErrorCode.WORKER_REQUIRED);
        assertThat(errorOf(FeatureKey.RESERVATION)).isEqualTo(ErrorCode.WORKER_REQUIRED);
    }

    @Test
    void 비활성화된_스탭의_PIN_인증은_만료된_것으로_본다() {
        pin(PIN_STAFF, NOW.plusMinutes(1));
        when(settings.isDeactivated(SHOP, PIN_STAFF)).thenReturn(true);

        assertThat(errorOf(FeatureKey.CONTACT_FULL)).isEqualTo(ErrorCode.PIN_REQUIRED);
        assertThat(guard.ownerPinVerified(session)).isFalse();
        // 공용 기능은 근무자(활성)로 그대로 통과
        assertThat(errorOf(FeatureKey.RESERVATION)).isNull();
    }

    @Test
    void 공용_기능은_근무자만_있으면_통과() {
        assertThat(errorOf(null)).isNull();
        assertThat(errorOf(FeatureKey.RESERVATION)).isNull();
    }

    @Test
    void PIN_기능은_PIN_인증이_없으면_pin_required() {
        assertThat(errorOf(FeatureKey.CONTACT_FULL)).isEqualTo(ErrorCode.PIN_REQUIRED);
    }

    @Test
    void PIN_인증이_유효하면_통과하고_만료를_5분_연장한다() {
        pin(PIN_STAFF, NOW.plusMinutes(1));

        assertThat(errorOf(FeatureKey.CONTACT_FULL)).isNull();
        assertThat(shopSession.pinExpiresAt(session)).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    void PIN_인증이_만료되면_pin_required() {
        pin(PIN_STAFF, NOW);

        assertThat(errorOf(FeatureKey.CONTACT_FULL)).isEqualTo(ErrorCode.PIN_REQUIRED);
    }

    @Test
    void 토글을_바꾸면_다음_요청부터_바로_반영된다() {
        assertThat(errorOf(FeatureKey.RESERVATION)).isNull();

        when(settings.lockedFeatures(SHOP)).thenReturn(EnumSet.of(FeatureKey.RESERVATION));

        assertThat(errorOf(FeatureKey.RESERVATION)).isEqualTo(ErrorCode.PIN_REQUIRED);
        assertThat(errorOf(FeatureKey.CONTACT_FULL)).isNull();
    }

    @Test
    void 고정_항목은_잠금_목록과_상관없이_정해진_대로다() {
        when(settings.lockedFeatures(SHOP))
                .thenReturn(EnumSet.of(FeatureKey.CONSENT, FeatureKey.DASHBOARD_SEARCH));

        assertThat(errorOf(FeatureKey.CONSENT)).isNull();
        assertThat(errorOf(FeatureKey.DASHBOARD_SEARCH)).isNull();
        assertThat(errorOf(FeatureKey.OWNER_PIN)).isEqualTo(ErrorCode.OWNER_PIN_REQUIRED);
    }

    @Test
    void 원장_PIN은_PIN_인증_스탭이_원장일_때만_통과() {
        pin(PIN_STAFF, NOW.plusMinutes(3));
        assertThat(errorOf(FeatureKey.OWNER_PIN)).isEqualTo(ErrorCode.OWNER_PIN_REQUIRED);

        pin(OWNER, NOW.plusMinutes(3));
        assertThat(errorOf(FeatureKey.OWNER_PIN)).isNull();
    }

    @Test
    void 기록에_남는_사람은_공용이면_현재_근무자_잠긴_기능이면_PIN_인증_스탭() {
        pin(PIN_STAFF, NOW.plusMinutes(3));

        assertThat(guard.actorStaffId(session, FeatureKey.RESERVATION)).isEqualTo(WORKER);
        assertThat(guard.actorStaffId(session, FeatureKey.CONTACT_FULL)).isEqualTo(PIN_STAFF);
    }

    @Test
    void 로그인하지_않았으면_unauthorized() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> guard.check(session, FeatureKey.RESERVATION))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatCode(() -> guard.check(session, (FeatureKey) null))
                .isInstanceOf(ApiException.class);
    }
}

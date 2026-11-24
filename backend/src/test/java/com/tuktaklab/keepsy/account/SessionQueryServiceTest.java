package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tuktaklab.keepsy.account.SessionQueryService.SessionView;
import com.tuktaklab.keepsy.account.SessionQueryService.StaffRef;
import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.FeatureKey.Access;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 세션 조회 응답 (Keepsy #16 완료 조건). DB 없이 돈다. */
class SessionQueryServiceTest {

    private static final long SHOP = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);
    private static final Map<FeatureKey, Access> FEATURES =
            Map.of(FeatureKey.RESERVATION, Access.PUBLIC, FeatureKey.CONTACT_FULL, Access.PIN);

    private final ShopSession shopSession = new ShopSession();
    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final FeatureGuard featureGuard = mock(FeatureGuard.class);
    private final SessionQueryService service =
            new SessionQueryService(
                    shopSession,
                    shopRepository,
                    staffRepository,
                    featureGuard,
                    Clock.fixed(NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE));
    private final MockHttpSession session = new MockHttpSession();

    private static Staff staff(long id, String name) {
        Staff staff = mock(Staff.class);
        when(staff.getId()).thenReturn(id);
        when(staff.getName()).thenReturn(name);
        return staff;
    }

    @BeforeEach
    void signedIn() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(SHOP, null, List.of()));
        when(shopRepository.findById(SHOP))
                .thenReturn(Optional.of(Shop.create("owner@example.com", "hash", "테스트 네일")));
        Staff worker = staff(10L, "근무자");
        Staff owner = staff(20L, "원장");
        when(staffRepository.findByIdAndShopId(10L, SHOP)).thenReturn(Optional.of(worker));
        when(staffRepository.findByIdAndShopId(20L, SHOP)).thenReturn(Optional.of(owner));
        when(featureGuard.accessByFeature(SHOP)).thenReturn(FEATURES);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 근무자가_없어도_매장_이름과_기능별_설정을_준다() {
        SessionView view = service.current(session);

        assertThat(view.shopId()).isEqualTo(SHOP);
        assertThat(view.shopName()).isEqualTo("테스트 네일");
        assertThat(view.currentStaff()).isNull();
        assertThat(view.pinStaff()).isNull();
        assertThat(view.pinExpiresAt()).isNull();
        assertThat(view.features()).isEqualTo(FEATURES);
    }

    @Test
    void 근무자와_PIN_인증_스탭_이름을_주고_조회로는_만료를_연장하지_않는다() {
        shopSession.setCurrentStaffId(session, 10L);
        shopSession.setPin(session, 20L, NOW.plusMinutes(3));

        SessionView view = service.current(session);

        assertThat(view.currentStaff()).isEqualTo(new StaffRef(10L, "근무자"));
        assertThat(view.pinStaff()).isEqualTo(new StaffRef(20L, "원장"));
        assertThat(view.pinExpiresAt()).isEqualTo(TimeConfig.offset(NOW.plusMinutes(3)));
        assertThat(shopSession.pinExpiresAt(session)).isEqualTo(NOW.plusMinutes(3));
    }

    @Test
    void PIN_인증이_만료됐으면_인증_스탭을_보여주지_않는다() {
        shopSession.setPin(session, 20L, NOW);

        SessionView view = service.current(session);

        assertThat(view.pinStaff()).isNull();
        assertThat(view.pinExpiresAt()).isNull();
    }

    @Test
    void 다른_매장_스탭_ID가_세션에_있어도_보여주지_않는다() {
        shopSession.setCurrentStaffId(session, 99L);
        when(staffRepository.findByIdAndShopId(99L, SHOP)).thenReturn(Optional.empty());

        assertThat(service.current(session).currentStaff()).isNull();
    }
}

package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 스탭 PIN 확인과 매장 단위 실패 차단 (Keepsy #17, C22). DB 없이 돈다. */
class StaffPinVerifierTest {

    private static final long SHOP = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);

    /** 테스트를 빠르게 하려고 BCrypt 비용만 낮췄다 */
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final StaffPinVerifier verifier =
            new StaffPinVerifier(
                    shopRepository,
                    staffRepository,
                    encoder,
                    Clock.fixed(NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE));

    private final Shop shop = Shop.create("owner@example.com", "hash", "테스트 네일");
    private final Staff owner = Staff.owner(SHOP, "원장", encoder.encode("1234"));
    private final Staff other = Staff.owner(SHOP, "다른 스탭", encoder.encode("5678"));
    private final Staff notAllowed = staffWithoutLockedFeatures("9999");

    /** "잠긴 기능 사용 허용"이 꺼진 일반 스탭 (스탭 등록 #21 전이라 목으로 만든다) */
    private Staff staffWithoutLockedFeatures(String pin) {
        Staff staff = mock(Staff.class);
        when(staff.getPinHash()).thenReturn(encoder.encode(pin));
        when(staff.isLockedFeatureAllowed()).thenReturn(false);
        return staff;
    }

    @BeforeEach
    void activeStaff() {
        when(shopRepository.findForUpdateById(SHOP)).thenReturn(Optional.of(shop));
        when(staffRepository.findByShopIdAndStatus(SHOP, StaffStatus.ACTIVE))
                .thenReturn(List.of(owner, other, notAllowed));
    }

    @Test
    void 잠긴_기능_인증은_허용된_스탭만_통과하고_허용이_꺼진_스탭의_PIN은_실패로_센다() {
        assertThatThrownBy(() -> verifier.verify(SHOP, "9999", true))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.PIN_INVALID);
        assertThat(shop.getPinFailCount()).isEqualTo(1);

        assertThat(verifier.verify(SHOP, "1234", true)).isSameAs(owner);
    }

    @Test
    void 근무자_전환은_잠긴_기능_허용과_상관없이_통과한다() {
        assertThat(verifier.verify(SHOP, "9999", false)).isSameAs(notAllowed);
    }

    private ErrorCode errorOf(String pin) {
        try {
            verifier.verify(SHOP, pin, false);
            return null;
        } catch (ApiException e) {
            return e.errorCode();
        }
    }

    @Test
    void 맞는_PIN의_활성_스탭을_돌려주고_실패_횟수는_되돌리지_않는다() {
        shop.pinFailed(NOW);

        assertThat(verifier.verify(SHOP, "5678", false)).isSameAs(other);
        assertThat(shop.getPinFailCount()).isEqualTo(1);
    }

    @Test
    void 원장_PIN_추측_사이에_자기_PIN으로_성공해도_5번째_실패에서_막힌다() {
        // F60, Keepsy #333: 잠긴 기능 PIN 4회 실패 → 근무자 전환 성공 → 다시 실패
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> verifier.verify(SHOP, "0000", true))
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.PIN_INVALID);
        }
        assertThat(verifier.verify(SHOP, "9999", false)).isSameAs(notAllowed);

        assertThat(errorOf("0000")).isEqualTo(ErrorCode.PIN_LOCKED);
    }

    @Test
    void 차단이_끝나면_맞는_PIN으로_인증되고_횟수는_0부터_다시_센다() {
        for (int i = 0; i < 5; i++) {
            errorOf("0000");
        }
        StaffPinVerifier later =
                new StaffPinVerifier(
                        shopRepository,
                        staffRepository,
                        encoder,
                        Clock.fixed(
                                NOW.plus(Shop.PIN_LOCK).atZone(TimeConfig.ZONE).toInstant(),
                                TimeConfig.ZONE));

        assertThat(later.verify(SHOP, "1234", false)).isSameAs(owner);
        assertThat(shop.getPinFailCount()).isZero();
    }

    @Test
    void 맞는_스탭이_없으면_pin_invalid이고_실패를_센다() {
        assertThat(errorOf("0000")).isEqualTo(ErrorCode.PIN_INVALID);
        assertThat(shop.getPinFailCount()).isEqualTo(1);
    }

    @Test
    void 다섯_번째_실패에서_pin_locked이고_차단_중에는_맞는_PIN도_거부한다() {
        for (int i = 0; i < 4; i++) {
            assertThat(errorOf("0000")).isEqualTo(ErrorCode.PIN_INVALID);
        }
        assertThat(errorOf("0000")).isEqualTo(ErrorCode.PIN_LOCKED);
        assertThat(errorOf("1234")).isEqualTo(ErrorCode.PIN_LOCKED);
    }
}

package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
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

    @BeforeEach
    void activeStaff() {
        when(shopRepository.findForUpdateById(SHOP)).thenReturn(Optional.of(shop));
        when(staffRepository.findByShopIdAndStatus(SHOP, StaffStatus.ACTIVE))
                .thenReturn(List.of(owner, other));
    }

    private ErrorCode errorOf(String pin) {
        try {
            verifier.verify(SHOP, pin);
            return null;
        } catch (ApiException e) {
            return e.errorCode();
        }
    }

    @Test
    void 맞는_PIN의_활성_스탭을_돌려주고_실패_횟수를_되돌린다() {
        shop.pinFailed(NOW);

        assertThat(verifier.verify(SHOP, "5678")).isSameAs(other);
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

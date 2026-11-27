package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 가입 저장 결과 (Keepsy #14 완료 조건). 실제 MySQL과 V1 스키마가 필요하다 (로컬 compose 또는 CI 서비스 컨테이너, DEVELOPMENT
 * 7장). 각 테스트는 끝나면 되돌린다.
 */
@SpringBootTest
@Transactional
class ShopSignupServiceTest {

    @Autowired ShopSignupService signupService;
    @Autowired ShopInfoService infoService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void 가입하면_매장과_원장_스탭이_생기고_비밀번호와_PIN은_BCrypt로_저장된다() {
        long shopId =
                signupService.signUp(
                        "signup-test@example.com", "password1", "테스트 네일", "원장", "1234");

        Map<String, Object> shop = jdbc.queryForMap("SELECT * FROM shop WHERE id = ?", shopId);
        assertThat(shop.get("name")).isEqualTo("테스트 네일");
        assertThat(passwordEncoder.matches("password1", (String) shop.get("password_hash")))
                .isTrue();

        Map<String, Object> owner =
                jdbc.queryForMap("SELECT * FROM staff WHERE shop_id = ?", shopId);
        assertThat(owner.get("name")).isEqualTo("원장");
        assertThat(owner.get("owner")).isEqualTo(true);
        assertThat(owner.get("locked_feature_allowed")).isEqualTo(true);
        assertThat(owner.get("status")).isEqualTo("ACTIVE");
        assertThat(passwordEncoder.matches("1234", (String) owner.get("pin_hash"))).isTrue();
    }

    @Test
    void 가입_때_고른_업종이_계약_순서로_저장되고_고르지_않으면_5개_모두_켠다() {
        long chosen =
                signupService.signUp(
                        "industry-test@example.com",
                        "password1",
                        "테스트 네일",
                        "원장",
                        "1234",
                        "02-000-0000",
                        List.of("ETC", "NAIL"));
        long unchosen =
                signupService.signUp(
                        "industry-all@example.com", "password1", "테스트 네일", "원장", "1234");

        assertThat(industriesOf(chosen)).isEqualTo("NAIL,ETC");
        assertThat(industriesOf(unchosen)).isEqualTo("NAIL,LASH,WAXING,SEMI_PERMANENT,ETC");
    }

    @Test
    void 가입_때_받은_매장_연락처를_저장하고_매장_정보_조회로_돌려준다() {
        // F73: 가입 연락처는 PUT /api/shop의 phone과 같은 칸이다. 형식 · 필수 검사는 ShopControllerTest
        long shopId =
                signupService.signUp(
                        "phone-test@example.com",
                        "password1",
                        "테스트 네일",
                        "원장",
                        "1234",
                        "1588-0000",
                        List.of("NAIL"));

        assertThat(jdbc.queryForObject("SELECT phone FROM shop WHERE id = ?", String.class, shopId))
                .isEqualTo("1588-0000");
        assertThat(infoService.get(shopId).phone()).isEqualTo("1588-0000");
    }

    @Test
    void 업종이_규칙을_어기면_invalid이고_매장이_생기지_않는다() {
        assertThatThrownBy(() ->
                        signupService.signUp(
                                "industry-bad@example.com",
                                "password1",
                                "테스트 네일",
                                "원장",
                                "1234",
                                "02-000-0000",
                                List.of("HAIR")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM shop WHERE email = ?",
                                Integer.class,
                                "industry-bad@example.com"))
                .isZero();
    }

    private String industriesOf(long shopId) {
        return jdbc.queryForObject(
                "SELECT industries FROM shop WHERE id = ?", String.class, shopId);
    }

    @Test
    void 가입한_매장에는_명세서_기본_설정값이_들어간다() {
        long shopId =
                signupService.signUp(
                        "defaults-test@example.com", "password1", "테스트 네일", "원장", "1234");

        Map<String, Object> shop = jdbc.queryForMap("SELECT * FROM shop WHERE id = ?", shopId);
        assertThat(shop.get("pin_locked_features"))
                .isEqualTo(
                        "CONTACT_FULL,CUSTOMER_EDIT,SALES_ANALYTICS,PAYMENT_EDIT,CAMPAIGN_VIEW,CAMPAIGN_SEND");
        assertThat(shop.get("churn_signal_cap_days")).isEqualTo(120);
        assertThat(shop.get("attribution_days")).isEqualTo(14);
        assertThat(shop.get("daily_send_limit")).isEqualTo(500);
        assertThat(shop.get("resend_block_days")).isEqualTo(7);
        assertThat(shop.get("retention_years")).isEqualTo(3);
        assertThat(shop.get("virtual_unit_price")).isEqualTo(0);
        assertThat(shop.get("nail_cycle_min_days")).isEqualTo(21);
        assertThat(shop.get("semi_permanent_cycle_max_days")).isEqualTo(730);
    }

    @Test
    void 이미_가입한_이메일이면_duplicate() {
        signupService.signUp("dup-test@example.com", "password1", "첫 매장", "원장", "1234");

        assertThatThrownBy(() ->
                        signupService.signUp(
                                "dup-test@example.com", "password2", "둘째 매장", "원장", "5678"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE);
    }
}

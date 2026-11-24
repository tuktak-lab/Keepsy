package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 로그인 실패 잠금을 실제 MySQL 행 잠금으로 확인한다 (Keepsy #15, F14). 동시 요청을 보내야 해서 테스트 트랜잭션으로 감싸지 않고, 만든 매장은 끝나면
 * 지운다. 로컬 compose 또는 CI 서비스 컨테이너가 필요하다.
 */
@SpringBootTest
class ShopLoginServiceTest {

    @Autowired ShopSignupService signupService;
    @Autowired ShopLoginService loginService;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> createdShops = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (long shopId : createdShops) {
            jdbc.update("DELETE FROM staff WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shop WHERE id = ?", shopId);
        }
    }

    private String shop(String email) {
        createdShops.add(signupService.signUp(email, "password1", "테스트 네일", "원장", "1234"));
        return email;
    }

    private ErrorCode loginError(String email, String password) {
        try {
            loginService.login(email, password);
            return null;
        } catch (ApiException e) {
            return e.errorCode();
        }
    }

    @Test
    void 맞으면_로그인되고_틀리면_어느_쪽이_틀렸는지_말하지_않는다() {
        String email = shop("login-ok@example.com");

        assertThat(loginService.login(email, "password1").shopName()).isEqualTo("테스트 네일");
        assertThat(loginError(email, "wrong-password")).isEqualTo(ErrorCode.LOGIN_FAILED);
        assertThat(loginError("nobody@example.com", "password1")).isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    void 실패_횟수는_실패해도_저장되고_5번째에_잠기며_잠금_중에는_맞는_비밀번호도_거부한다() {
        String email = shop("login-lock@example.com");

        for (int i = 0; i < 4; i++) {
            assertThat(loginError(email, "wrong-password")).isEqualTo(ErrorCode.LOGIN_FAILED);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT login_fail_count FROM shop WHERE email = ?",
                                Integer.class,
                                email))
                .isEqualTo(4);

        assertThat(loginError(email, "wrong-password")).isEqualTo(ErrorCode.LOGIN_LOCKED);
        assertThatThrownBy(() -> loginService.login(email, "password1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.LOGIN_LOCKED);
    }

    @Test
    void 성공하면_실패_횟수가_0으로_돌아간다() {
        String email = shop("login-reset@example.com");
        loginError(email, "wrong-password");
        loginError(email, "wrong-password");

        loginService.login(email, "password1");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT login_fail_count FROM shop WHERE email = ?",
                                Integer.class,
                                email))
                .isZero();
    }

    @Test
    void 틀린_비밀번호_동시_요청_10개도_5번째에서_잠기고_잠금을_우회하지_못한다() throws Exception {
        String email = shop("login-race@example.com");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ErrorCode>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(
                    pool.submit(() -> {
                        start.await();
                        return loginError(email, "wrong-password");
                    }));
        }
        start.countDown();
        List<ErrorCode> codes = new ArrayList<>();
        for (Future<ErrorCode> result : results) {
            codes.add(result.get());
        }
        pool.shutdown();

        // 행 잠금으로 한 줄씩 처리되면 1~4번째는 실패, 5번째에 잠기고, 6~10번째는 잠금 중
        assertThat(codes).filteredOn(c -> c == ErrorCode.LOGIN_FAILED).hasSize(4);
        assertThat(codes).filteredOn(c -> c == ErrorCode.LOGIN_LOCKED).hasSize(6);
        assertThat(loginError(email, "password1")).isEqualTo(ErrorCode.LOGIN_LOCKED);
    }
}

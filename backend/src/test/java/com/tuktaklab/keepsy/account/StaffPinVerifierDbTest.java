package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;

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
 * PIN 실패 차단을 실제 MySQL 행 잠금으로 확인한다 (Keepsy #17, C22 · F14). 동시 요청을 보내야 해서 테스트 트랜잭션으로 감싸지 않고, 만든 매장은
 * 끝나면 지운다. 로컬 compose 또는 CI 서비스 컨테이너가 필요하다.
 */
@SpringBootTest
class StaffPinVerifierDbTest {

    @Autowired ShopSignupService signupService;
    @Autowired StaffPinVerifier verifier;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> createdShops = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (long shopId : createdShops) {
            jdbc.update("DELETE FROM staff WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shop WHERE id = ?", shopId);
        }
    }

    private long shop(String email) {
        long shopId = signupService.signUp(email, "password1", "테스트 네일", "원장", "1234");
        createdShops.add(shopId);
        return shopId;
    }

    private ErrorCode errorOf(long shopId, String pin) {
        try {
            verifier.verify(shopId, pin);
            return null;
        } catch (ApiException e) {
            return e.errorCode();
        }
    }

    @Test
    void 비활성_스탭의_PIN으로는_근무자가_될_수_없다() {
        long shopId = shop("pin-inactive@example.com");
        assertThat(verifier.verify(shopId, "1234").getName()).isEqualTo("원장");

        jdbc.update("UPDATE staff SET status = 'INACTIVE' WHERE shop_id = ?", shopId);

        assertThat(errorOf(shopId, "1234")).isEqualTo(ErrorCode.PIN_INVALID);
    }

    @Test
    void 틀린_PIN_동시_요청_10개도_5번째에서_막히고_차단을_우회하지_못한다() throws Exception {
        long shopId = shop("pin-race@example.com");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ErrorCode>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(
                    pool.submit(() -> {
                        start.await();
                        return errorOf(shopId, "0000");
                    }));
        }
        start.countDown();
        List<ErrorCode> codes = new ArrayList<>();
        for (Future<ErrorCode> result : results) {
            codes.add(result.get());
        }
        pool.shutdown();

        // 행 잠금으로 한 줄씩 처리되면 1~4번째는 틀림, 5번째에 막히고, 6~10번째는 차단 중
        assertThat(codes).filteredOn(c -> c == ErrorCode.PIN_INVALID).hasSize(4);
        assertThat(codes).filteredOn(c -> c == ErrorCode.PIN_LOCKED).hasSize(6);
        assertThat(errorOf(shopId, "1234")).isEqualTo(ErrorCode.PIN_LOCKED);
    }
}

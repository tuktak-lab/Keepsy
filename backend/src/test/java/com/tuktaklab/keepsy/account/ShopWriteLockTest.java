package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.account.ShopInfoService.ShopInfo;
import com.tuktaklab.keepsy.account.ShopInfoService.ShopInfoChange;
import com.tuktaklab.keepsy.support.TestShopData;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매장 설정 · 정보 저장이 PIN 실패 기록을 덮어쓰지 않는다 (Keepsy #332, C22). 도우미 트랜잭션이 매장 행을 잡은 동안 저장이 그 잠금을 기다리는 것을
 * 확인한 뒤 도우미가 PIN 실패를 커밋한다. 실제 MySQL에서 돈다.
 */
@SpringBootTest
class ShopWriteLockTest {

    @Autowired ShopSettingsService settingsService;
    @Autowired ShopInfoService infoService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 설정_저장과_동시에_PIN이_틀려도_실패_횟수가_남는다() throws Exception {
        Shop.Settings s = settingsService.settings(shop);
        Shop.Settings changed =
                new Shop.Settings(
                        s.churnSignalCapDays(),
                        s.attributionDays(),
                        s.dailySendLimit() + 1,
                        s.resendBlockDays(),
                        s.virtualFailurePercent(),
                        s.retentionYears());

        whilePinFails(() -> settingsService.updateSettings(shop, changed));

        assertThat(pinFailCount()).isEqualTo(3);
        assertThat(settingsService.settings(shop).dailySendLimit())
                .isEqualTo(s.dailySendLimit() + 1);
    }

    @Test
    void 매장_정보_저장과_동시에_PIN이_틀려도_실패_횟수가_남는다() throws Exception {
        ShopInfo info = infoService.get(shop);

        whilePinFails(() ->
                infoService.update(
                        shop,
                        new ShopInfoChange(
                                "바뀐 매장",
                                info.phone(),
                                info.senderNumber(),
                                info.unsubscribeNumber(),
                                info.openingHours())));

        assertThat(pinFailCount()).isEqualTo(3);
        assertThat(infoService.get(shop).name()).isEqualTo("바뀐 매장");
    }

    /**
     * 도우미 트랜잭션이 매장 행을 잡은 동안 {@code save}를 다른 스레드에서 실행하고, 그 스레드가 잠금을 기다리기 시작하면 도우미가 PIN 실패 3회를 기록해
     * 커밋한다. {@code save}가 던진 예외는 그대로 다시 던진다.
     */
    private void whilePinFails(Runnable save) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> saving =
                    tx.execute(status -> {
                        jdbc.queryForObject(
                                "SELECT id FROM shop WHERE id = ? FOR UPDATE", Long.class, shop);
                        Future<?> f = pool.submit(save);
                        awaitLockWait(f);
                        jdbc.update("UPDATE shop SET pin_fail_count = 3 WHERE id = ?", shop);
                        return f;
                    });
            saving.get();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 다른 연결이 매장 행 잠금을 기다리기 시작할 때까지. 고치기 전에는 저장의 {@code UPDATE}, 고친 뒤에는 {@code FOR UPDATE} 조회가 기다린다.
     * CI 계정은 {@code performance_schema} 권한이 없어 같은 계정의 연결이 보이는 {@code
     * information_schema.PROCESSLIST}를 쓴다.
     */
    private void awaitLockWait(Future<?> save) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.PROCESSLIST"
                                + " WHERE ID <> CONNECTION_ID() AND ("
                                + " LOWER(INFO) LIKE 'update shop set%'"
                                + " OR (LOWER(INFO) LIKE '%from shop %'"
                                + " AND LOWER(INFO) LIKE '%for update%'))",
                        Integer.class)
                == 0) {
            if (save.isDone() || System.nanoTime() > deadline) {
                throw new AssertionError("저장이 매장 잠금을 기다리지 않았습니다");
            }
            Thread.onSpinWait();
        }
    }

    private int pinFailCount() {
        return jdbc.queryForObject(
                "SELECT pin_fail_count FROM shop WHERE id = ?", Integer.class, shop);
    }
}

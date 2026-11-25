package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.customer.CustomerService.TagInput;
import com.tuktaklab.keepsy.support.TestShopData;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예상할 수 있는 업무 충돌을 그 기능에서 처리한다 (Keepsy #338, F63). CSV 가져오기의 번호 중복 경쟁은 {@code duplicate}, 취향 태그 동시
 * 편집은 고객 잠금으로 충돌 자체가 없다. 실제 MySQL에서 돈다.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class CustomerConflictTest {

    private static final String HEADER = "이름,휴대폰,성별,출생연도,메모,방문일,시술카테고리\n";
    private static final String PHONE = "010-0000-7001";

    @Autowired CustomerImportService imports;
    @Autowired CustomerService customers;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void CSV_가져오기_중_같은_번호가_먼저_등록되면_duplicate이고_파일_전체를_되돌리며_로그에_번호가_없다(CapturedOutput output)
            throws Exception {
        byte[] csv =
                (HEADER + "모의 A," + PHONE + ",,,,,\n" + "모의 B,010-0000-7002,,,,,\n")
                        .getBytes(StandardCharsets.UTF_8);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> importing;
        try {
            // 가져오기가 번호를 확인한 뒤 다른 요청이 같은 번호를 커밋하는 경우: 도우미가 같은 번호를 넣고 커밋 전에 가져오기의 INSERT가 기다리게 한다
            importing =
                    tx.execute(status -> {
                        jdbc.update(
                                "INSERT INTO customer (shop_id, name, phone, registered_at,"
                                        + " reservation_restricted) VALUES (?, '모의 선점', ?, ?, false)",
                                shop,
                                PHONE,
                                LocalDateTime.of(2026, 1, 1, 0, 0));
                        Future<?> f = pool.submit(() -> imports.importCsv(shop, staff, csv, true));
                        awaitInsertWait(f);
                        return f;
                    });
            ExecutionException e = catchThrowableOfType(ExecutionException.class, importing::get);

            assertThat(e.getCause())
                    .isInstanceOf(ApiException.class)
                    .extracting(c -> ((ApiException) c).errorCode())
                    .isEqualTo(ErrorCode.DUPLICATE);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM customer WHERE shop_id = ?", shop)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(PHONE).doesNotContain("Duplicate entry");
    }

    @Test
    void 같은_고객의_취향_태그를_동시에_바꿔도_모두_성공한다() throws Exception {
        long customer = data.customer(shop, false, null);
        List<TagInput> tags =
                List.of(
                        new TagInput("NAIL", "누드톤"),
                        new TagInput("NAIL", "글리터"),
                        new TagInput("LASH", "C컬"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                String memo = "메모 " + i;
                results.add(
                        pool.submit(() -> {
                            start.await();
                            customers.changePreferences(shop, customer, tags, memo);
                            return null;
                        }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(); // 하나라도 실패하면 ExecutionException
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM preference_tag WHERE customer_id = ?", customer))
                .isEqualTo(3);
    }

    /**
     * 가져오기의 고객 {@code INSERT}가 도우미가 넣은 같은 번호의 고유 키 잠금을 기다리기 시작할 때까지. CI 계정은 {@code
     * performance_schema} 권한이 없어 같은 계정의 연결이 보이는 {@code information_schema.PROCESSLIST}를 쓴다.
     */
    private void awaitInsertWait(Future<?> importing) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (count(
                        "SELECT count(*) FROM information_schema.PROCESSLIST"
                                + " WHERE ID <> CONNECTION_ID() AND LOWER(INFO) LIKE 'insert into customer %'")
                == 0) {
            if (importing.isDone() || System.nanoTime() > deadline) {
                throw new AssertionError("가져오기가 같은 번호의 잠금을 기다리지 않았습니다");
            }
            Thread.onSpinWait();
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}

package com.tuktaklab.keepsy.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.customer.CustomerService;
import com.tuktaklab.keepsy.support.TestShopData;
import com.tuktaklab.keepsy.visit.Closing;
import com.tuktaklab.keepsy.visit.PaymentMethod;
import com.tuktaklab.keepsy.visit.VisitAppService;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutionException;
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
 * 예약 상태 명령과 마감 · 고객 삭제의 갱신 유실 (Keepsy #331). 도우미 트랜잭션이 예약 행을 먼저 잡고, 명령이 그 잠금을 기다리는 것을 {@code
 * data_lock_waits}로 확인한 뒤 도우미가 마감 · 삭제를 커밋한다. 실제 MySQL에서 돈다.
 */
@SpringBootTest
class ReservationLockTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    @Autowired ReservationAppService reservations;
    @Autowired VisitAppService visits;
    @Autowired CustomerService customers;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;
    private long gel;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        gel = data.menu(shop, 60);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 마감과_동시에_취소해도_마감_결과가_남는다() throws Exception {
        long id = reserve("메모");

        Throwable canceled =
                whileLocked(
                        id,
                        () -> reservations.cancel(shop, id),
                        () ->
                                visits.close(
                                        shop,
                                        id,
                                        new Closing(null, 50000, PaymentMethod.CARD, null, false),
                                        staff));

        // 마감이 먼저 커밋됐으니 확정 예약이 아니라 취소는 거절된다
        assertThat(canceled).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?", String.class, id))
                .isEqualTo("COMPLETED");
        Long visitId =
                jdbc.queryForObject(
                        "SELECT visit_id FROM reservation WHERE id = ?", Long.class, id);
        assertThat(visitId).isNotNull();
        assertThat(count("SELECT count(*) FROM visit WHERE shop_id = ?", shop)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment WHERE visit_id = ?", visitId)).isEqualTo(1);
    }

    @Test
    void 고객_삭제와_동시에_취소해도_비운_메모가_되살아나지_않는다() throws Exception {
        long id = reserve("개인 메모");

        whileLocked(
                id, () -> reservations.cancel(shop, id), () -> customers.delete(shop, customer));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?", String.class, id))
                .isEqualTo("CANCELED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT memo FROM reservation WHERE id = ?", String.class, id))
                .isNull();
    }

    private long reserve(String memo) {
        return reservations.create(
                shop,
                new NewReservation(
                        customer, staff, DAY.atTime(11, 0), null, null, false, memo, List.of(gel)),
                false);
    }

    /**
     * 도우미 트랜잭션이 예약 행을 잡은 동안 {@code command}를 다른 스레드에서 실행하고, 그 스레드가 잠금을 기다리기 시작하면 도우미 안에서 {@code
     * first}를 실행해 커밋한다.
     *
     * @return {@code command}가 던진 예외 (성공했으면 null)
     */
    private Throwable whileLocked(long reservationId, Runnable command, Runnable first)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiting =
                    tx.execute(status -> {
                        jdbc.queryForObject(
                                "SELECT id FROM reservation WHERE id = ? FOR UPDATE",
                                Long.class,
                                reservationId);
                        Future<?> f = pool.submit(command);
                        awaitLockWait(f);
                        first.run();
                        return f;
                    });
            try {
                waiting.get();
                return null;
            } catch (ExecutionException e) {
                return e.getCause();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 다른 트랜잭션이 행 잠금을 기다리기 시작할 때까지 (고정 sleep 대신 조건 polling) */
    private void awaitLockWait(Future<?> command) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (count("SELECT count(*) FROM performance_schema.data_lock_waits") == 0) {
            if (command.isDone() || System.nanoTime() > deadline) {
                throw new AssertionError("명령이 예약 잠금을 기다리지 않았습니다");
            }
            Thread.onSpinWait();
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}

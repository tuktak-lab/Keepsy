package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
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
 * 방문 수정 · 삭제 잠금 순서 (Keepsy #337). 도우미 트랜잭션이 마감과 같은 순서(고객 → 예약)로 잠근 채 방문 수정 · 삭제를 다른 스레드에서 실행하고, 그
 * 스레드가 잠금을 기다리는 것을 확인한 뒤 도우미 안에서 마감을 마저 하고 커밋한다. 실제 MySQL에서 돈다.
 */
@SpringBootTest
class VisitEditLockTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    @Autowired VisitAppService visits;
    @Autowired ReservationAppService reservations;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;
    private long gel; // NAIL
    private long lash; // LASH
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        gel = data.menu(shop, 60);
        lash = data.menu(shop, 30);
        jdbc.update("UPDATE service_menu SET category = 'LASH' WHERE id = ?", lash);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 추가_결제_마감과_방문_삭제가_동시에_와도_교착_없이_둘_다_된다() throws Exception {
        long reservation = reserve(DAY, gel);
        long visit = close(reservation);

        // 수정 전: 삭제가 방문을 쥔 채 예약을 기다리고, 마감은 예약을 쥔 채 결제 FK로 방문을 기다려 교착
        Throwable deleted =
                whileLocked(
                        reservation,
                        () -> {
                            visits.deleteVisit(shop, visit);
                            return null;
                        },
                        () -> close(reservation));

        assertThat(deleted).isNull();
        assertThat(count("SELECT count(*) FROM visit WHERE shop_id = ?", shop)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?",
                                String.class,
                                reservation))
                .isEqualTo("CONFIRMED");
        // 첫 마감 · 추가 결제 모두 방문 연결이 풀려 이력으로 남는다
        assertThat(
                        count(
                                "SELECT count(*) FROM payment WHERE shop_id = ? AND visit_id IS NULL",
                                shop))
                .isEqualTo(2);
    }

    @Test
    void 다른_날_마감과_방문_수정이_동시에_와도_예측이_최종_방문_기준이다() throws Exception {
        long first = reserve(DAY, gel);
        long firstVisit = close(first);
        long later = reserve(DAY.plusDays(30), gel);

        // 첫 방문을 LASH로 고친다. 그사이 커밋된 다음 달 NAIL 방문을 봐야 NAIL 예측이 남는다
        Throwable edited =
                whileLocked(
                        later,
                        () -> {
                            visits.editVisit(
                                    shop,
                                    firstVisit,
                                    List.of(new Closing.Item(lash, staff, false)));
                            return null;
                        },
                        () -> close(later));

        assertThat(edited).isNull();
        assertThat(lastServiceDate("NAIL")).isEqualTo(DAY.plusDays(30));
        assertThat(lastServiceDate("LASH")).isEqualTo(DAY);
    }

    private long reserve(LocalDate day, long menu) {
        return reservations.create(
                shop,
                new NewReservation(
                        customer, staff, day.atTime(11, 0), null, null, false, null, List.of(menu)),
                false);
    }

    private long close(long reservationId) {
        return visits.close(
                shop,
                reservationId,
                new Closing(null, 50000, PaymentMethod.CARD, null, false),
                staff);
    }

    private LocalDate lastServiceDate(String category) {
        return jdbc.queryForObject(
                "SELECT last_service_date FROM customer_prediction WHERE customer_id = ? AND category = ?",
                LocalDate.class,
                customer,
                category);
    }

    /**
     * 도우미 트랜잭션이 마감과 같은 순서(고객 → 예약)로 잠근 채 {@code command}를 다른 스레드에서 시작하고, 그 스레드가 잠금을 기다리기 시작하면 도우미
     * 안에서 {@code closing}(마감)을 실행하고 커밋한다.
     *
     * @return {@code command}가 던진 예외 (성공했으면 null)
     */
    private Throwable whileLocked(long reservationId, Callable<?> command, Callable<?> closing)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiting =
                    tx.execute(status -> {
                        try {
                            jdbc.queryForObject(
                                    "SELECT id FROM customer WHERE id = ? FOR UPDATE",
                                    Long.class,
                                    customer);
                            jdbc.queryForObject(
                                    "SELECT id FROM reservation WHERE id = ? FOR UPDATE",
                                    Long.class,
                                    reservationId);
                            Future<?> f = pool.submit(command);
                            awaitLockWait(f);
                            closing.call();
                            return f;
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
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

    /**
     * 다른 연결이 도우미가 쥔 행을 기다리기 시작할 때까지 (고정 sleep 대신 조건 polling). 수정 후에는 고객 {@code FOR UPDATE}, 수정 전에는
     * 예약 {@code UPDATE}(삭제의 예약 되돌리기)에서 기다린다. 수정 전 방문 수정은 고객 잠금 없이 예측을 쓰다가 고객 FK 검사에서 기다린다. CI 계정은
     * {@code performance_schema} 권한이 없어 같은 계정의 연결이 보이는 {@code information_schema.PROCESSLIST}를 쓴다.
     */
    private void awaitLockWait(Future<?> command) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (count(
                        "SELECT count(*) FROM information_schema.PROCESSLIST"
                                + " WHERE ID <> CONNECTION_ID() AND ("
                                + " (LOWER(INFO) LIKE '%from customer %'"
                                + " AND LOWER(INFO) LIKE '%for update%')"
                                + " OR LOWER(INFO) LIKE 'update reservation set%'"
                                + " OR LOWER(INFO) LIKE '%customer_prediction%')")
                == 0) {
            if (command.isDone() || System.nanoTime() > deadline) {
                throw new AssertionError("방문 수정 · 삭제가 잠금을 기다리지 않았습니다");
            }
            Thread.onSpinWait();
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}

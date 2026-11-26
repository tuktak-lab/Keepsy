package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
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
 * 시술 마감 잠금 순서 (Keepsy #336). 도우미 트랜잭션이 앞선 작업(같은 고객의 다른 마감 · 고객 삭제의 예약 정리)을 커밋하지 않고 고객 잠금을 쥔 동안 뒤
 * 마감을 다른 스레드에서 실행하고, 그 마감이 고객 잠금을 기다리는 것을 확인한 뒤 커밋한다. 실제 MySQL에서 돈다.
 */
@SpringBootTest
class VisitClosingLockTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    @Autowired VisitAppService visits;
    @Autowired ReservationAppService reservations;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static final Callable<?> NOTHING = () -> null;

    private TestShopData data;
    private long shop;
    private long staff;
    private long staff2;
    private long gel; // NAIL
    private long lash; // LASH
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        staff2 = data.staff(shop, "ACTIVE", false);
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
    void 같은_고객_다른_날_동시_마감에도_두_카테고리_예측이_최신이다() throws Exception {
        close(reserve(staff, DAY, gel)); // 기존 NAIL 예측이 있는 고객
        long nail = reserve(staff, DAY.plusDays(30), gel);
        long lashDay = reserve(staff, DAY.plusDays(31), lash);

        Throwable later = whileCustomerLocked(() -> close(nail), () -> close(lashDay), NOTHING);

        assertThat(later).isNull();
        assertThat(lastServiceDate("NAIL")).isEqualTo(DAY.plusDays(30));
        assertThat(lastServiceDate("LASH")).isEqualTo(DAY.plusDays(31));
        assertThat(count("SELECT count(*) FROM visit WHERE shop_id = ?", shop)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM payment WHERE shop_id = ?", shop)).isEqualTo(3);
    }

    @Test
    void 같은_고객_같은_카테고리_다른_날_동시_마감에도_예측이_두_방문_기준이다() throws Exception {
        long first = reserve(staff, DAY, gel);
        long second = reserve(staff, DAY.plusDays(30), gel);

        Throwable later = whileCustomerLocked(() -> close(first), () -> close(second), NOTHING);

        // 수정 전: 뒤 마감이 앞 방문 · 첫 예측 행을 못 보고 예측 행을 새로 넣어 uk_customer_prediction 충돌
        assertThat(later).isNull();
        assertThat(lastServiceDate("NAIL")).isEqualTo(DAY.plusDays(30));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT basis_visit_count FROM customer_prediction"
                                        + " WHERE customer_id = ? AND category = 'NAIL'",
                                Integer.class,
                                customer))
                .isEqualTo(2);
    }

    @Test
    void 같은_고객_같은_날_동시_마감은_방문_하나에_두_카테고리_예측() throws Exception {
        long nail = reserve(staff, DAY, gel);
        long lashSameDay = reserve(staff2, DAY, lash);

        Throwable later = whileCustomerLocked(() -> close(nail), () -> close(lashSameDay), NOTHING);

        assertThat(later).isNull();
        assertThat(count("SELECT count(*) FROM visit WHERE shop_id = ?", shop)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment WHERE shop_id = ?", shop)).isEqualTo(2);
        assertThat(lastServiceDate("NAIL")).isEqualTo(DAY);
        assertThat(lastServiceDate("LASH")).isEqualTo(DAY);
    }

    @Test
    void 고객_삭제와_마감이_동시에_와도_교착_없이_마감이_거절된다() throws Exception {
        long id = reserve(staff, DAY, gel);

        // 고객 삭제는 고객 잠금 → 예약 정리 순서다 (CustomerService.delete). 고객을 잠근 뒤 마감이 들어오고, 마감이 기다리는 동안 예약을
        // 정리한다. 수정 전에는 마감이 예약을 쥔 채 고객을 기다려 교착. delete 자체는 접근 기록에 요청 세션이 필요해 예약 정리만 부른다
        Throwable later =
                whileCustomerLocked(
                        () -> null,
                        () -> close(id),
                        () -> {
                            reservations.releaseForDeletedCustomer(shop, customer);
                            return null;
                        });

        assertThat(later)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?", String.class, id))
                .isEqualTo("CANCELED");
        assertThat(count("SELECT count(*) FROM visit WHERE shop_id = ?", shop)).isZero();
    }

    private long reserve(long staffId, LocalDate day, long menu) {
        return reservations.create(
                shop,
                new NewReservation(
                        customer,
                        staffId,
                        day.atTime(11, 0),
                        null,
                        null,
                        false,
                        null,
                        List.of(menu)),
                false);
    }

    private long close(long reservationId) {
        return visits.close(
                shop,
                reservationId,
                new Closing(null, 50000, PaymentMethod.CARD, null, false),
                staff);
    }

    private void lockCustomer() {
        jdbc.queryForObject(
                "SELECT id FROM customer WHERE id = ? FOR UPDATE", Long.class, customer);
    }

    private LocalDate lastServiceDate(String category) {
        return jdbc.queryForObject(
                "SELECT last_service_date FROM customer_prediction WHERE customer_id = ? AND category = ?",
                LocalDate.class,
                customer,
                category);
    }

    /**
     * 도우미 트랜잭션에서 {@code first}를 실행해 고객 잠금을 쥔 채로 {@code later}를 다른 스레드에서 시작하고, 그 스레드가 고객 잠금을 기다리기
     * 시작하면 도우미 안에서 {@code afterWait}를 실행하고 커밋한다.
     *
     * @return {@code later}가 던진 예외 (성공했으면 null)
     */
    private Throwable whileCustomerLocked(
            Callable<?> first, Callable<?> later, Callable<?> afterWait) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiting =
                    tx.execute(status -> {
                        try {
                            first.call();
                            lockCustomer(); // first가 고객을 잠그지 않는 경로여도 쥐고 있게
                            Future<?> f = pool.submit(later);
                            awaitCustomerLockWait(f);
                            afterWait.call();
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
     * 다른 연결이 고객 행 잠금을 기다리기 시작할 때까지 (고정 sleep 대신 조건 polling). 도우미가 고객 행을 쥐고 있으니 다른 연결에서 실행 중인 고객
     * {@code FOR UPDATE} 조회는 대기 중이다. CI 계정은 {@code performance_schema} 권한이 없어 같은 계정의 연결이 보이는 {@code
     * information_schema.PROCESSLIST}를 쓴다.
     */
    private void awaitCustomerLockWait(Future<?> later) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (count(
                        "SELECT count(*) FROM information_schema.PROCESSLIST"
                                + " WHERE ID <> CONNECTION_ID()"
                                + " AND LOWER(INFO) LIKE '%from customer %'"
                                + " AND LOWER(INFO) LIKE '%for update%'")
                == 0) {
            if (later.isDone() || System.nanoTime() > deadline) {
                throw new AssertionError("뒤 마감이 고객 잠금을 기다리지 않았습니다");
            }
            Thread.onSpinWait();
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}

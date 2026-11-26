package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 결제 수정 · 취소 (Keepsy #68 · #69)와 방문 삭제 = 마감 되돌리기 (#71). 실제 MySQL에서 돈다. */
@SpringBootTest
class VisitDeleteAndPaymentTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired VisitAppService service;
    @Autowired ReservationAppService reservations;
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

    private long reserve(Long customerId, int hour) {
        return reservations.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        DAY.atTime(hour, 0),
                        null,
                        null,
                        false,
                        null,
                        List.of(gel)),
                false);
    }

    private long closeWithPayment(long reservationId, String memo) {
        return service.close(
                shop,
                reservationId,
                new Closing(null, 50000, PaymentMethod.CARD, memo, false),
                staff);
    }

    private long paymentOf(long reservationId) {
        return jdbc.queryForObject(
                "SELECT id FROM payment WHERE reservation_id = ? ORDER BY id LIMIT 1",
                Long.class,
                reservationId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(code);
    }

    @Test
    void 결제는_금액과_수단을_고칠_수_있고_취소하면_방문은_남는다() {
        long r = reserve(customer, 11);
        long visitId = closeWithPayment(r, null);
        long payment = paymentOf(r);

        service.updatePayment(shop, payment, 45000, PaymentMethod.TRANSFER);
        service.cancelPayment(shop, payment);

        assertThat(
                        jdbc.queryForMap(
                                "SELECT amount, method, canceled, visit_id FROM payment WHERE id = ?",
                                payment))
                .containsEntry("amount", 45000)
                .containsEntry("method", "TRANSFER")
                .containsEntry("canceled", true)
                .containsEntry("visit_id", visitId);
        assertThat(count("SELECT COUNT(*) FROM visit WHERE id = ?", visitId)).isEqualTo(1);
        assertError(() -> service.cancelPayment(shop, payment), ErrorCode.CONFLICT);
        assertError(
                () -> service.updatePayment(shop, payment, 1000, PaymentMethod.CASH),
                ErrorCode.CONFLICT);
        assertError(() -> service.cancelPayment(data.shop(), payment), ErrorCode.NOT_FOUND);
    }

    @Test
    void 방문을_지우면_합쳐진_예약은_모두_확정으로_결제는_취소로_시술_메모_사진은_사라진다() {
        long morning = reserve(customer, 11);
        long afternoon = reserve(customer, 15);
        long visitId = closeWithPayment(morning, "메모");
        assertThat(closeWithPayment(afternoon, null)).isEqualTo(visitId);
        jdbc.update(
                "INSERT INTO photo (shop_id, visit_id, storage_key, uploaded_at) VALUES (?, ?, 'no-such-file', ?)",
                shop,
                visitId,
                LocalDateTime.of(2026, 11, 3, 12, 0));
        assertThat(service.hasActivePayments(shop, visitId)).isTrue();

        service.deleteVisit(shop, visitId);

        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM reservation WHERE id IN (?, ?) AND visit_id IS NULL",
                                String.class,
                                morning,
                                afternoon))
                .containsExactly("CONFIRMED", "CONFIRMED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT canceled FROM payment WHERE reservation_id IN (?, ?) AND visit_id IS NULL",
                                Boolean.class,
                                morning,
                                afternoon))
                .containsExactly(true, true);
        assertThat(count("SELECT COUNT(*) FROM visit WHERE id = ?", visitId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM visit_service WHERE visit_id = ?", visitId))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM visit_note WHERE visit_id = ?", visitId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM photo WHERE visit_id = ?", visitId)).isZero();

        // 되돌린 예약은 다시 마감할 수 있고, 취소된 결제는 되살아나지 않는다
        long again = closeWithPayment(morning, null);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM payment WHERE visit_id = ? AND canceled = FALSE",
                                again))
                .isEqualTo(1);
    }

    @Test
    void 고객_없음으로_마감한_방문은_지울_수_없다() {
        long r = reserve(null, 11);
        long visitId =
                service.close(
                        shop, r, new Closing(null, 30000, PaymentMethod.CASH, null, true), staff);

        assertError(() -> service.deleteVisit(shop, visitId), ErrorCode.CONFLICT);
        assertError(() -> service.deleteVisit(data.shop(), visitId), ErrorCode.NOT_FOUND);
    }

    @Test
    void 같은_방문을_동시에_지우면_하나만_성공한다() throws Exception {
        long visitId = closeWithPayment(reserve(customer, 11), null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(
                    pool.submit(() -> {
                        start.await();
                        service.deleteVisit(shop, visitId);
                        return null;
                    }));
        }
        start.countDown();

        int ok = 0;
        int notFound = 0;
        for (Future<?> f : results) {
            try {
                f.get();
                ok++;
            } catch (ExecutionException e) {
                assertThat(((ApiException) e.getCause()).errorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND);
                notFound++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
        assertThat(notFound).isEqualTo(1);
    }
}

package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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

/** 결제 없이 완료 처리와 방문 자동 생성 (Keepsy #62 · #63 완료 조건). 실제 MySQL에서 돈다. */
@SpringBootTest
class VisitAppServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    @Autowired VisitAppService service;
    @Autowired ReservationAppService reservations;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;
    private long staff2;
    private long gel; // NAIL 60분
    private long lash; // LASH 30분
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

    private long reserve(
            Long customerId, long staffId, int hour, boolean consult, List<Long> menus) {
        return reservations.create(
                shop,
                new NewReservation(
                        customerId, staffId, DAY.atTime(hour, 0), null, null, consult, null, menus),
                false);
    }

    private Map<String, Object> reservationRow(long id) {
        return jdbc.queryForMap("SELECT status, visit_id FROM reservation WHERE id = ?", id);
    }

    private List<Map<String, Object>> services(long visitId) {
        return jdbc.queryForList(
                "SELECT reservation_id, service_menu_id, category, staff_id FROM visit_service"
                        + " WHERE visit_id = ? ORDER BY id",
                visitId);
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(code);
    }

    @Test
    void 완료하면_예약일로_방문이_생기고_예정_시술이_실제_시술로_복사된다() {
        long r = reserve(customer, staff, 11, false, List.of(gel, lash));

        long visitId = service.completeWithoutPayment(shop, r, null);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT visit_date FROM visit WHERE id = ?",
                                LocalDate.class,
                                visitId))
                .isEqualTo(DAY);
        assertThat(reservationRow(r))
                .containsEntry("status", "COMPLETED")
                .containsEntry("visit_id", visitId);
        assertThat(services(visitId))
                .extracting(s -> s.get("category"))
                .containsExactly("NAIL", "LASH");
        assertThat(services(visitId)).allMatch(s -> s.get("staff_id").equals(staff));
    }

    @Test
    void 같은_고객_같은_날_두_예약은_방문_하나에_시술을_더한다() {
        long morning = reserve(customer, staff, 11, false, List.of(gel));
        long afternoon = reserve(customer, staff2, 15, false, List.of(lash));

        long v1 = service.completeWithoutPayment(shop, morning, null);
        long v2 = service.completeWithoutPayment(shop, afternoon, null);

        assertThat(v2).isEqualTo(v1);
        assertThat(services(v1))
                .extracting(s -> s.get("staff_id"))
                .containsExactly(staff, staff2); // 시술마다 담당 스탭
    }

    @Test
    void 고객_없는_예약과_확정이_아닌_예약은_완료할_수_없다() {
        long noCustomer = reserve(null, staff, 11, false, List.of(gel));
        long canceled = reserve(customer, staff, 13, false, List.of(gel));
        reservations.cancel(shop, canceled);

        assertError(
                () -> service.completeWithoutPayment(shop, noCustomer, null), ErrorCode.CONFLICT);
        assertError(() -> service.completeWithoutPayment(shop, canceled, null), ErrorCode.CONFLICT);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM visit WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
    }

    @Test
    void 상담_후_진행_예약은_실제_시술을_확인해야_완료되고_그_시술로_기록된다() {
        long r = reserve(customer, staff, 11, true, List.of(gel));

        assertError(() -> service.completeWithoutPayment(shop, r, null), ErrorCode.INVALID);
        assertThat(reservationRow(r)).containsEntry("status", "CONFIRMED");

        long visitId = service.completeWithoutPayment(shop, r, List.of(lash));
        assertThat(services(visitId)).extracting(s -> s.get("category")).containsExactly("LASH");
    }

    @Test
    void 다른_매장_예약이나_메뉴면_not_found() {
        long r = reserve(customer, staff, 11, false, List.of(gel));
        long otherShop = data.shop();
        long otherMenu = data.menu(otherShop, 30);

        assertError(() -> service.completeWithoutPayment(otherShop, r, null), ErrorCode.NOT_FOUND);
        assertError(
                () -> service.completeWithoutPayment(shop, r, List.of(otherMenu)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void 같은_고객의_두_예약을_동시에_완료해도_방문은_하나다() throws Exception {
        long a = reserve(customer, staff, 11, false, List.of(gel));
        long b = reserve(customer, staff2, 11, false, List.of(lash));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Long> fa =
                pool.submit(() -> {
                    start.await();
                    return service.completeWithoutPayment(shop, a, null);
                });
        Future<Long> fb =
                pool.submit(() -> {
                    start.await();
                    return service.completeWithoutPayment(shop, b, null);
                });
        start.countDown();
        long va = fa.get();
        long vb = fb.get();
        pool.shutdown();

        assertThat(va).isEqualTo(vb);
        assertThat(services(va)).hasSize(2);
    }
}

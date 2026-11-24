package com.tuktaklab.keepsy.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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

/**
 * 예약 가능 시간 · 예약 등록 (Keepsy #54 · #55 완료 조건). 실제 MySQL(로컬 compose, CI 서비스 컨테이너)에서 돈다 (DEVELOPMENT
 * 7장). 동시 저장을 확인하려고 테스트 트랜잭션으로 감싸지 않고, 끝나면 만든 행을 지운다.
 */
@SpringBootTest
class ReservationAppServiceTest {

    /** 화요일. 모의 매장은 매일 10:00~19:00 영업 */
    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    @Autowired ReservationAppService service;
    @Autowired ReservationRepository reservations;
    @Autowired JdbcTemplate jdbc;

    private long shop;
    private long otherShop;
    private long staff;
    private long inactiveStaff;
    private long nail; // 90분
    private long care; // 30분
    private long customer;
    private long restricted;

    private TestShopData data;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        otherShop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        inactiveStaff = data.staff(shop, "INACTIVE", false);
        nail = data.menu(shop, 90);
        care = data.menu(shop, 30);
        customer = data.customer(shop, false, null);
        restricted = data.customer(shop, true, "노쇼 3회");
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private NewReservation at(int hour, Long customerId, List<Long> menus) {
        return new NewReservation(
                customerId, staff, DAY.atTime(hour, 0), null, null, false, null, menus);
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(code);
    }

    @Test
    void 등록하면_확정_상태이고_소요_시간_기본값은_예정_시술_합계다() {
        long id = service.create(shop, at(11, customer, List.of(nail, care)), false);

        Reservation saved = reservations.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(saved.getEndAt()).isEqualTo(DAY.atTime(13, 0)); // 90 + 30분
        assertThat(saved.getChannel()).isEqualTo(ReservationChannel.STORE);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM reservation_service WHERE reservation_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(2);
    }

    @Test
    void 예약된_시간은_선택지에서_빠지고_겹치게_저장하면_reservation_conflict() {
        service.create(shop, at(12, null, List.of(nail)), false); // 고객 없는 예약도 시간을 막는다 (E3)

        List<LocalTime> times = service.availableTimes(shop, staff, DAY, 60);
        assertThat(times).doesNotContain(LocalTime.of(11, 30), LocalTime.of(13, 0));
        assertThat(times).contains(LocalTime.of(11, 0), LocalTime.of(13, 30));

        assertError(
                () -> service.create(shop, at(13, customer, List.of(care)), false),
                ErrorCode.RESERVATION_CONFLICT);
    }

    @Test
    void 휴무일에는_선택지가_없고_저장도_안_된다() {
        jdbc.update(
                "INSERT INTO day_off (shop_id, staff_id, off_date) VALUES (?, ?, ?)",
                shop,
                staff,
                DAY);

        assertThat(service.availableTimes(shop, staff, DAY, 60)).isEmpty();
        assertError(
                () -> service.create(shop, at(11, customer, List.of(care)), false),
                ErrorCode.RESERVATION_CONFLICT);
    }

    @Test
    void 비활성_스탭은_invalid_다른_매장_스탭_메뉴_고객은_not_found() {
        assertError(
                () ->
                        service.create(
                                shop,
                                new NewReservation(
                                        customer,
                                        inactiveStaff,
                                        DAY.atTime(11, 0),
                                        null,
                                        null,
                                        false,
                                        null,
                                        List.of(care)),
                                false),
                ErrorCode.INVALID);
        assertError(
                () -> service.create(otherShop, at(11, null, List.of(care)), false),
                ErrorCode.NOT_FOUND);
        long otherMenu = data.menu(otherShop, 30);
        assertError(
                () -> service.create(shop, at(11, customer, List.of(otherMenu)), false),
                ErrorCode.NOT_FOUND);
        long otherCustomer = data.customer(otherShop, false, null);
        assertError(
                () -> service.create(shop, at(11, otherCustomer, List.of(care)), false),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void 예약_제한_고객은_원장_PIN이_있어야_예약된다() {
        assertThatThrownBy(() -> service.create(shop, at(11, restricted, List.of(care)), false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("노쇼 3회");

        assertThat(service.create(shop, at(11, restricted, List.of(care)), true)).isPositive();
    }

    @Test
    void 동시에_같은_시간을_잡으면_하나만_저장된다() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Long> task =
                    () -> {
                        start.await();
                        return service.create(shop, at(15, null, List.of(nail)), false);
                    };
            results.add(pool.submit(task));
        }
        start.countDown();

        int saved = 0;
        int conflicts = 0;
        for (Future<Long> f : results) {
            try {
                f.get();
                saved++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ApiException.class);
                assertThat(((ApiException) e.getCause()).errorCode())
                        .isEqualTo(ErrorCode.RESERVATION_CONFLICT);
                conflicts++;
            }
        }
        pool.shutdown();

        assertThat(saved).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
    }

    /** 지난 날짜에 잡아 둔 예약 (노쇼는 시작 시각이 지나야 한다). 날짜가 흘러도 테스트가 바뀌지 않게 먼 과거 · 먼 미래를 쓴다 */
    private long reservationOn(LocalDate date, Long customerId) {
        return service.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        date.atTime(11, 0),
                        null,
                        null,
                        false,
                        null,
                        List.of(care)),
                false);
    }

    @Test
    void 취소하면_그_시간을_다시_잡을_수_있고_두_번_취소하면_conflict() {
        long id = service.create(shop, at(11, customer, List.of(care)), false);
        service.cancel(shop, id);

        assertThat(reservations.findById(id).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELED);
        assertThat(service.create(shop, at(11, customer, List.of(care)), false)).isPositive();
        assertError(() -> service.cancel(shop, id), ErrorCode.CONFLICT);
    }

    @Test
    void 노쇼는_시작_시각이_지난_뒤에만_되고_고객_없는_예약도_된다() {
        long past = reservationOn(LocalDate.of(2020, 1, 7), null);
        long future = reservationOn(LocalDate.of(2099, 1, 6), customer);

        service.markNoShow(shop, past);

        assertThat(reservations.findById(past).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.NO_SHOW);
        assertError(() -> service.markNoShow(shop, future), ErrorCode.CONFLICT);
    }

    @Test
    void 고객_연결은_고객_없는_예약에만_되고_예약_제한_고객이면_안내만_돌려준다() {
        long id = service.create(shop, at(11, null, List.of(care)), false);

        assertThat(service.linkCustomer(shop, id, restricted))
                .hasValueSatisfying(notice -> assertThat(notice).contains("노쇼 3회"));
        assertThat(reservations.findById(id).orElseThrow().getCustomerId()).isEqualTo(restricted);
        assertError(() -> service.linkCustomer(shop, id, customer), ErrorCode.CONFLICT);
    }

    @Test
    void 다른_매장_예약이나_고객이면_not_found() {
        long id = service.create(shop, at(11, null, List.of(care)), false);
        long otherCustomer = data.customer(otherShop, false, null);

        assertError(() -> service.cancel(otherShop, id), ErrorCode.NOT_FOUND);
        assertError(() -> service.markNoShow(otherShop, id), ErrorCode.NOT_FOUND);
        assertError(() -> service.linkCustomer(shop, id, otherCustomer), ErrorCode.NOT_FOUND);
    }
}

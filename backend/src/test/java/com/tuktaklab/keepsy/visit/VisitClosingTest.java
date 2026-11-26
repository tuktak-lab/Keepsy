package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import com.tuktaklab.keepsy.visit.Closing.Item;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 시술 마감 카드 저장 (Keepsy #65 · #66 · #67 완료 조건). 실제 MySQL에서 돈다. */
@SpringBootTest
class VisitClosingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired VisitAppService service;
    @Autowired ReservationAppService reservations;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;
    private long staff2;
    private long gel;
    private long care;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        staff2 = data.staff(shop, "ACTIVE", false);
        gel = data.menu(shop, 60);
        care = data.menu(shop, 30);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long reserve(Long customerId, int hour, boolean consult) {
        return reservations.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        DAY.atTime(hour, 0),
                        null,
                        null,
                        consult,
                        null,
                        List.of(gel)),
                false);
    }

    private static Closing pay(int amount, String memo) {
        return new Closing(null, amount, PaymentMethod.CARD, memo, false);
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

    private long reserveAt(LocalDateTime start) {
        return reservations.create(
                shop,
                new NewReservation(customer, staff, start, null, null, false, null, List.of(gel)),
                false);
    }

    @Test
    void 예약일이_오늘보다_늦으면_마감도_완료도_409이고_아무것도_바뀌지_않는다() {
        long r = reserveAt(LocalDate.now(TimeConfig.ZONE).plusDays(1).atTime(11, 0));

        assertError(() -> service.close(shop, r, pay(50000, "메모"), staff), ErrorCode.CONFLICT);
        assertError(() -> service.completeWithoutPayment(shop, r, null), ErrorCode.CONFLICT);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?", String.class, r))
                .isEqualTo("CONFIRMED");
        assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", r)).isZero();
        assertThat(count("SELECT COUNT(*) FROM visit WHERE customer_id = ?", customer)).isZero();
    }

    @Test
    void 오늘_예약은_시작_시각_전이어도_마감할_수_있다() {
        LocalDate today = LocalDate.now(TimeConfig.ZONE);
        long r = reserveAt(today.atTime(11, 0));
        // 영업시간 검사를 거치지 않게 시작 시각만 오늘 끝으로 옮긴다 (마감은 날짜만 본다, F71)
        jdbc.update("UPDATE reservation SET start_at = ? WHERE id = ?", today.atTime(23, 59), r);

        long visitId = service.close(shop, r, pay(50000, null), staff);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT visit_date FROM visit WHERE id = ?",
                                LocalDate.class,
                                visitId))
                .isEqualTo(today);
    }

    @Test
    void 저장_한_번으로_결제_방문_메모_예약_완료가_함께_처리된다() {
        long r = reserve(customer, 11, false);
        Closing in =
                new Closing(
                        List.of(new Item(gel, staff, false), new Item(care, staff2, true)),
                        70000,
                        PaymentMethod.CARD,
                        "다음엔 누드톤",
                        false);

        long visitId = service.close(shop, r, in, staff);

        assertThat(jdbc.queryForMap("SELECT status, visit_id FROM reservation WHERE id = ?", r))
                .containsEntry("status", "COMPLETED")
                .containsEntry("visit_id", visitId);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT customer_id, reservation_id, amount, method FROM payment WHERE visit_id = ?",
                                visitId))
                .containsEntry("customer_id", customer)
                .containsEntry("reservation_id", r)
                .containsEntry("amount", 70000)
                .containsEntry("method", "CARD");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT staff_id, content FROM visit_note WHERE visit_id = ?",
                                visitId))
                .containsEntry("staff_id", staff)
                .containsEntry("content", "다음엔 누드톤");
        List<Map<String, Object>> services =
                jdbc.queryForList(
                        "SELECT staff_id, cycle_excluded FROM visit_service WHERE visit_id = ? ORDER BY id",
                        visitId);
        assertThat(services).extracting(s -> s.get("staff_id")).containsExactly(staff, staff2);
        assertThat(services).extracting(s -> s.get("cycle_excluded")).containsExactly(false, true);
    }

    @Test
    void 완료된_예약에는_같은_방문에_결제와_메모만_더하고_실제_시술은_바꿀_수_없다() {
        long r = reserve(customer, 11, false);
        long visitId = service.close(shop, r, pay(50000, null), staff);

        assertThat(service.close(shop, r, pay(10000, "추가 파츠"), staff)).isEqualTo(visitId);
        assertThat(count("SELECT COUNT(*) FROM payment WHERE visit_id = ?", visitId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM visit_service WHERE visit_id = ?", visitId))
                .isEqualTo(1);

        Closing changeServices =
                new Closing(
                        List.of(new Item(care, staff, false)), 0, PaymentMethod.CASH, null, false);
        assertError(() -> service.close(shop, r, changeServices, staff), ErrorCode.INVALID);
    }

    // ---- 중복 제출 재현 (#352). 지금 동작을 기록한다. 서버 멱등성은 이 결과로 따로 정한다 ----

    private Closing firstClosing() {
        return new Closing(
                List.of(new Item(gel, staff, false)), 50000, PaymentMethod.CARD, "첫 메모", false);
    }

    private int payments(long visitId) {
        return count("SELECT COUNT(*) FROM payment WHERE visit_id = ?", visitId);
    }

    private int notes(long visitId) {
        return count("SELECT COUNT(*) FROM visit_note WHERE visit_id = ?", visitId);
    }

    @Test
    void 현재동작_정상_추가_결제는_같은_방문에_결제와_메모를_하나씩_더한다() {
        long r = reserve(customer, 11, false);
        long visitId = service.close(shop, r, firstClosing(), staff);

        service.close(shop, r, pay(10000, "추가 파츠"), staff);

        assertThat(payments(visitId)).isEqualTo(2);
        assertThat(notes(visitId)).isEqualTo(2);
    }

    @Test
    void 현재동작_첫_마감을_같은_본문으로_두_번_보내면_두_번째는_400이고_중복은_없다() {
        long r = reserve(customer, 11, false);
        long visitId = service.close(shop, r, firstClosing(), staff);

        // 두 번째는 완료된 예약의 추가 결제로 처리되는데 시술 목록이 있어 invalid. 문구가 중복 제출 상황과 맞지 않는다
        assertThatThrownBy(() -> service.close(shop, r, firstClosing(), staff))
                .isInstanceOf(ApiException.class)
                .hasMessage("완료된 예약의 실제 시술은 방문 수정에서 바꿔 주세요");
        assertThat(payments(visitId)).isEqualTo(1);
        assertThat(notes(visitId)).isEqualTo(1);
    }

    @Test
    void 현재동작_추가_결제를_두_번_보내면_결제와_메모가_중복된다() {
        long r = reserve(customer, 11, false);
        long visitId = service.close(shop, r, firstClosing(), staff);

        service.close(shop, r, pay(10000, "추가 파츠"), staff);
        service.close(shop, r, pay(10000, "추가 파츠"), staff);

        // payment에 멱등 제약이 없어 그대로 두 건 더 쌓인다
        assertThat(payments(visitId)).isEqualTo(3);
        assertThat(notes(visitId)).isEqualTo(3);
    }

    @Test
    void 현재동작_추가_결제_응답이_유실돼_다시_보내면_중복과_구별하지_못하고_한_번_더_저장된다() {
        long r = reserve(customer, 11, false);
        long visitId = service.close(shop, r, firstClosing(), staff);

        // 첫 요청은 서버에서 커밋됐지만 화면은 응답을 못 받았다고 본다
        service.close(shop, r, pay(10000, null), staff);
        // 화면의 재시도: 요청에 식별자가 없어 서버는 새 추가 결제로 받는다
        long retried = service.close(shop, r, pay(10000, null), staff);

        assertThat(retried).isEqualTo(visitId);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM payment WHERE visit_id = ? AND amount = 10000",
                                visitId))
                .isEqualTo(2);
    }

    @Test
    void 고객_없는_예약은_고객을_연결하거나_고객_없음으로만_마감한다() {
        long r = reserve(null, 11, false);

        assertError(() -> service.close(shop, r, pay(50000, null), staff), ErrorCode.CONFLICT);
        assertError(
                () ->
                        service.close(
                                shop,
                                r,
                                new Closing(null, 50000, PaymentMethod.CASH, "메모", true),
                                staff),
                ErrorCode.INVALID);

        long visitId =
                service.close(
                        shop, r, new Closing(null, 50000, PaymentMethod.CASH, null, true), staff);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT customer_id FROM visit WHERE id = ?", Long.class, visitId))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT customer_id FROM payment WHERE visit_id = ?",
                                Long.class,
                                visitId))
                .isNull();
        // 고객 없음으로 마감한 방문에는 나중에도 메모를 받지 않는다
        assertError(() -> service.close(shop, r, pay(1000, "메모"), staff), ErrorCode.INVALID);
    }

    @Test
    void 고객_없는_방문은_같은_날이어도_예약마다_따로_생긴다() {
        long a = reserve(null, 11, false);
        long b = reserve(null, 13, false);
        Closing noCustomer = new Closing(null, 30000, PaymentMethod.CASH, null, true);

        assertThat(service.close(shop, a, noCustomer, staff))
                .isNotEqualTo(service.close(shop, b, noCustomer, staff));
    }

    @Test
    void 취소_노쇼_예약과_상담_후_진행_미확인은_마감할_수_없다() {
        long canceled = reserve(customer, 11, false);
        reservations.cancel(shop, canceled);
        long consult = reserve(customer, 13, true);

        assertError(
                () -> service.close(shop, canceled, pay(1000, null), staff), ErrorCode.CONFLICT);
        assertError(() -> service.close(shop, consult, pay(1000, null), staff), ErrorCode.INVALID);
        assertThat(count("SELECT COUNT(*) FROM payment WHERE shop_id = ?", shop)).isZero();
        assertThat(count("SELECT COUNT(*) FROM visit WHERE shop_id = ?", shop)).isZero();
    }

    @Test
    void 결제_수단이_없거나_다른_매장_스탭이면_거부한다() {
        long r = reserve(customer, 11, false);
        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", false);

        assertError(
                () -> service.close(shop, r, new Closing(null, 1000, null, null, false), staff),
                ErrorCode.INVALID);
        assertError(
                () ->
                        service.close(
                                shop,
                                r,
                                new Closing(
                                        List.of(new Item(gel, otherStaff, false)),
                                        1000,
                                        PaymentMethod.CARD,
                                        null,
                                        false),
                                staff),
                ErrorCode.NOT_FOUND);
    }
}

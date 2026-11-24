package com.tuktaklab.keepsy.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.support.TestShopData;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 예약의 캠페인 귀속 · 승계 저장 (Keepsy #101 완료 조건, FR-VIS-04, FR-ANL-03). 판정 규칙 자체는 {@code AttributionTest}가
 * 본다. 실제 MySQL에서 돈다. 예약 생성 시각은 지금이라 발송 시각도 지금 기준으로 넣는다.
 */
@SpringBootTest
class ReservationAttributionTest {

    /** 예약 시간대. 생성 시각(지금)과 상관없이 겹치지만 않으면 된다 */
    private static final LocalDate DAY = LocalDate.of(2099, 1, 6);

    @Autowired ReservationAppService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private TestShopData data;
    private long shop;
    private long staff;
    private long nail;
    private long waxing;
    private long lash;
    private long customer;
    private LocalDateTime now;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        // 가상 발송기 스케줄러(3초마다)가 PENDING 행을 먼저 처리해도 결과가 같도록 실패 비율을 100%로 둔다 (PENDING → FAILED,
        // VirtualPhoneInboxTest와 같은 방식)
        jdbc.update("UPDATE shop SET virtual_failure_percent = 100 WHERE id = ?", shop);
        staff = data.staff(shop, "ACTIVE", false);
        nail = data.menu(shop, 30);
        waxing = menu("WAXING");
        lash = menu("LASH");
        customer = data.customer(shop, false, null);
        now = LocalDateTime.now(clock); // 예약 생성 시각과 같은 Asia/Seoul
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long menu(String category) {
        long id = data.menu(shop, 30);
        jdbc.update("UPDATE service_menu SET category = ? WHERE id = ?", category, id);
        return id;
    }

    /** 발송 성공한 광고 메시지 */
    private long sent(long customerId, String category, LocalDateTime at) {
        return message(customerId, "AD", category, false, "SENT", at);
    }

    private long message(
            long customerId,
            String kind,
            String category,
            boolean controlGroup,
            String status,
            LocalDateTime resultAt) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    PreparedStatement ps =
                            con.prepareStatement(
                                    "INSERT INTO campaign_message (shop_id, kind, customer_id, category,"
                                            + " control_group, send_status, result_at)"
                                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, shop);
                    ps.setString(2, kind);
                    ps.setLong(3, customerId);
                    ps.setString(4, category);
                    ps.setBoolean(5, controlGroup);
                    ps.setString(6, status);
                    ps.setObject(7, resultAt);
                    return ps;
                },
                key);
        return key.getKey().longValue();
    }

    private long reserve(Long customerId, int hour, List<Long> menus) {
        return reserveAt(customerId, DAY.atTime(hour, 0), menus);
    }

    private long reserveAt(Long customerId, LocalDateTime startAt, List<Long> menus) {
        return service.create(
                shop,
                new NewReservation(customerId, staff, startAt, null, null, false, null, menus),
                false);
    }

    /** 그 예약에서 그 메뉴 시술의 귀속 발송 ID */
    private Long attributed(long reservationId, long menuId) {
        return jdbc.queryForObject(
                "SELECT attributed_message_id FROM reservation_service"
                        + " WHERE reservation_id = ? AND service_menu_id = ?",
                Long.class,
                reservationId,
                menuId);
    }

    @Test
    void 등록하면_시술마다_같은_카테고리의_가장_최근_발송에_연결한다() {
        sent(customer, "NAIL", now.minusDays(5));
        long recentNail = sent(customer, "NAIL", now.minusDays(3));
        long wax = sent(customer, "WAXING", now.minusDays(2));

        long r = reserve(customer, 10, List.of(nail, waxing, lash));

        assertThat(attributed(r, nail)).isEqualTo(recentNail);
        assertThat(attributed(r, waxing)).isEqualTo(wax);
        assertThat(attributed(r, lash)).isNull(); // 받은 LASH 발송이 없다 (E7)
    }

    @Test
    void 실패_대조군_안내_메시지는_귀속에_쓰지_않는다() {
        long ok = sent(customer, "NAIL", now.minusDays(3));
        message(customer, "AD", "NAIL", false, "FAILED", now.minusDays(1));
        message(customer, "AD", "NAIL", true, null, now.minusDays(1)); // 대조군은 발송하지 않는다
        message(customer, "AD", "NAIL", false, "PENDING", null);
        message(customer, "CONSENT_RECONFIRM", null, false, "SENT", now.minusDays(1));

        long r = reserve(customer, 10, List.of(nail));

        assertThat(attributed(r, nail)).isEqualTo(ok);
    }

    @Test
    void 귀속_기간은_매장_설정에서_읽고_발송일_더하기_기간까지_연결한다() {
        jdbc.update("UPDATE shop SET attribution_days = 7 WHERE id = ?", shop);
        long inside = data.customer(shop, false, null);
        long outside = data.customer(shop, false, null);
        long edge = sent(inside, "NAIL", now.toLocalDate().minusDays(7).atStartOfDay());
        sent(outside, "NAIL", now.toLocalDate().minusDays(8).atTime(23, 59));

        long r1 = reserve(inside, 10, List.of(nail));
        long r2 = reserve(outside, 11, List.of(nail));

        assertThat(attributed(r1, nail)).isEqualTo(edge);
        assertThat(attributed(r2, nail)).isNull();
    }

    @Test
    void 이미_다른_예약이_가진_발송은_다음_예약에_연결하지_않는다() {
        long m = sent(customer, "NAIL", now.minusDays(1));

        long first = reserve(customer, 10, List.of(nail));
        long second = reserve(customer, 11, List.of(nail));

        assertThat(attributed(first, nail)).isEqualTo(m);
        assertThat(attributed(second, nail)).isNull();
    }

    @Test
    void 귀속_예약이_취소되면_같은_카테고리_다음_예약이_이어받고_취소_예약의_귀속은_남는다() {
        long m = sent(customer, "NAIL", now.minusDays(1));
        long first = reserve(customer, 10, List.of(nail));
        long otherCategory = reserve(customer, 11, List.of(waxing));
        long next = reserve(customer, 12, List.of(nail));

        service.cancel(shop, first);

        assertThat(attributed(next, nail)).isEqualTo(m);
        assertThat(attributed(otherCategory, waxing)).isNull();
        assertThat(attributed(first, nail)).isEqualTo(m); // 퍼널 재예약 단계에 남는다 (FR-ANL-01)
    }

    @Test
    void 귀속_예약이_노쇼되면_다음_예약이_이어받는다() {
        long m = sent(customer, "NAIL", now.minusDays(1));
        long past = reserveAt(customer, LocalDateTime.of(2020, 1, 7, 11, 0), List.of(nail));
        long next = reserve(customer, 10, List.of(nail));

        service.markNoShow(shop, past);

        assertThat(attributed(next, nail)).isEqualTo(m);
    }

    @Test
    void 이어받을_예약이_없으면_그대로_두고_그_뒤_새_예약이_이어받는다() {
        long m = sent(customer, "NAIL", now.minusDays(1));
        long first = reserve(customer, 10, List.of(nail));

        service.cancel(shop, first);
        long later = reserve(customer, 11, List.of(nail));

        assertThat(attributed(later, nail)).isEqualTo(m);
    }

    @Test
    void 고객_없는_예약은_고객을_연결할_때_예약_생성_시각을_기준으로_계산한다() {
        long r = reserve(null, 10, List.of(nail));
        assertThat(attributed(r, nail)).isNull();
        LocalDateTime createdAt = now.minusDays(10);
        jdbc.update("UPDATE reservation SET created_at = ? WHERE id = ?", createdAt, r);
        long before = sent(customer, "NAIL", now.minusDays(20)); // 생성 10일 전, 연결 시각으로는 20일 전
        sent(customer, "NAIL", now.minusDays(1)); // 예약 생성 뒤 발송이라 연결하지 않는다

        service.linkCustomer(shop, r, customer);

        assertThat(attributed(r, nail)).isEqualTo(before);
    }
}

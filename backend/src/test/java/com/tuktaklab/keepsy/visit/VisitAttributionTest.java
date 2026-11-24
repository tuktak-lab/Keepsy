package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
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
 * 방문의 캠페인 귀속 저장 (Keepsy #101 완료 조건, FR-VIS-04, FR-CUS-13, F33). 실제 MySQL에서 돈다. 날짜 기준 계산은 예약을 지난 날짜로
 * 잡아 예약 생성 시각(지금) 기준 귀속과 섞이지 않게 하고(귀속되지 않은 예약), 예약 승계는 발송을 지금 기준으로 넣어 예약이 귀속되게 한다.
 */
@SpringBootTest
class VisitAttributionTest {

    private static final LocalDate DAY = LocalDate.of(2020, 1, 7);

    @Autowired VisitAppService service;
    @Autowired ReservationAppService reservations;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private TestShopData data;
    private long shop;
    private long staff;
    private long nail;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        nail = data.menu(shop, 30);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long sent(String category, LocalDateTime at) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    PreparedStatement ps =
                            con.prepareStatement(
                                    "INSERT INTO campaign_message (shop_id, kind, customer_id, category,"
                                            + " control_group, send_status, result_at)"
                                            + " VALUES (?, 'AD', ?, ?, FALSE, 'SENT', ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, shop);
                    ps.setLong(2, customer);
                    ps.setString(3, category);
                    ps.setObject(4, at);
                    return ps;
                },
                key);
        return key.getKey().longValue();
    }

    /** 예약을 잡고 결제 없이 완료해 방문을 만든다 */
    private long visitOn(LocalDate date) {
        return service.completeWithoutPayment(shop, reserve(date, List.of(nail)), null);
    }

    private long reserve(LocalDate date, List<Long> menus) {
        return reservations.create(
                shop,
                new NewReservation(
                        customer, staff, date.atTime(11, 0), null, null, false, null, menus),
                false);
    }

    private Long attributed(long visitId) {
        return jdbc.queryForObject(
                "SELECT attributed_message_id FROM visit_service WHERE visit_id = ?",
                Long.class,
                visitId);
    }

    private Long attributed(long visitId, long menuId) {
        return jdbc.queryForObject(
                "SELECT attributed_message_id FROM visit_service"
                        + " WHERE visit_id = ? AND service_menu_id = ?",
                Long.class,
                visitId,
                menuId);
    }

    private Long reservationAttributed(long reservationId) {
        return jdbc.queryForObject(
                "SELECT attributed_message_id FROM reservation_service WHERE reservation_id = ?",
                Long.class,
                reservationId);
    }

    @Test
    void 방문이_생기면_귀속_기간_안의_같은_카테고리_발송에_연결하고_다음_방문은_연결하지_않는다() {
        long m = sent("NAIL", DAY.minusDays(2).atTime(10, 0));
        sent("WAXING", DAY.minusDays(1).atTime(10, 0)); // 다른 카테고리 (E7)

        long first = visitOn(DAY);
        long second = visitOn(DAY.plusDays(3));

        assertThat(attributed(first)).isEqualTo(m);
        assertThat(attributed(second)).isNull(); // 첫 방문이 이미 가졌다
    }

    @Test
    void 귀속된_예약을_마감한_방문은_방문일이_귀속_기간_밖이어도_예약의_발송을_이어받는다() {
        // 11/1 발송 → 11/3 예약 → 11/20 방문 (기간 14일, F33): 발송 2일 뒤 예약, 발송 18일 뒤 방문
        LocalDateTime now = LocalDateTime.now(clock);
        long m = sent("NAIL", now.minusDays(2));

        long r = reserve(now.toLocalDate().plusDays(16), List.of(nail));
        long visitId = service.completeWithoutPayment(shop, r, null);

        assertThat(reservationAttributed(r)).isEqualTo(m);
        assertThat(attributed(visitId)).isEqualTo(m);
    }

    @Test
    void 예약에_없던_카테고리_시술은_이어받지_않고_방문일_기준으로_따로_계산한다() {
        long waxing = data.menu(shop, 30);
        jdbc.update("UPDATE service_menu SET category = 'WAXING' WHERE id = ?", waxing);
        LocalDateTime now = LocalDateTime.now(clock);
        long m = sent("NAIL", now.minusDays(2));
        sent("WAXING", now.minusDays(2)); // 방문일 기준 18일 전이라 따로 계산하면 기간 밖

        long r = reserve(now.toLocalDate().plusDays(16), List.of(nail));
        long visitId = service.completeWithoutPayment(shop, r, List.of(nail, waxing));

        assertThat(attributed(visitId, nail)).isEqualTo(m);
        assertThat(attributed(visitId, waxing)).isNull();
    }

    @Test
    void 귀속되지_않은_예약의_방문은_방문일_기준으로_계산한다() {
        long m = sent("NAIL", DAY.minusDays(2).atTime(10, 0)); // 예약 생성 시각(지금)에서는 기간 밖

        long r = reserve(DAY, List.of(nail));
        long visitId = service.completeWithoutPayment(shop, r, null);

        assertThat(reservationAttributed(r)).isNull();
        assertThat(attributed(visitId)).isEqualTo(m);
    }

    @Test
    void 가져온_방문에_합쳐지면_귀속된_예약이어도_이어받지_않는다() {
        LocalDateTime now = LocalDateTime.now(clock);
        sent("NAIL", now.minusDays(2));
        LocalDate day = now.toLocalDate().plusDays(16);
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'IMPORT')",
                shop,
                customer,
                day);

        long r = reserve(day, List.of(nail));
        long visitId = service.completeWithoutPayment(shop, r, null);

        assertThat(reservationAttributed(r)).isNotNull();
        assertThat(attributed(visitId)).isNull(); // FR-CUS-13
    }

    @Test
    void 귀속_기간이_지난_발송은_연결하지_않는다() {
        sent("NAIL", DAY.minusDays(15).atTime(10, 0));

        assertThat(attributed(visitOn(DAY))).isNull();
    }

    @Test
    void 가져온_방문에_합쳐진_시술은_귀속하지_않는다() {
        sent("NAIL", DAY.minusDays(2).atTime(10, 0));
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'IMPORT')",
                shop,
                customer,
                DAY);

        long visitId = visitOn(DAY); // 같은 고객 · 같은 날이라 가져온 방문에 합쳐진다 (C6)

        assertThat(
                        jdbc.queryForObject(
                                "SELECT source FROM visit WHERE id = ?", String.class, visitId))
                .isEqualTo("IMPORT");
        assertThat(attributed(visitId)).isNull();
    }
}

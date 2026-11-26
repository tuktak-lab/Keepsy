package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 방문이 생기거나 지워지면 같은 트랜잭션에서 예측을 다시 계산한다 (FR-PRD-01, #197 연동). 실제 MySQL에서 돈다. */
@SpringBootTest
class VisitPredictionTest {

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

    private List<Date> lastServiceDates() {
        return jdbc.queryForList(
                "SELECT last_service_date FROM customer_prediction WHERE customer_id = ?",
                Date.class,
                customer);
    }

    @Test
    void 완료하면_예측이_생기고_방문을_지우면_예측도_지워진다() {
        long visitId = service.completeWithoutPayment(shop, reserve(customer, 11), null);

        assertThat(lastServiceDates()).containsExactly(Date.valueOf(DAY));

        service.deleteVisit(shop, visitId);

        assertThat(lastServiceDates()).isEmpty();
    }

    @Test
    void 마감_카드로_방문이_생겨도_예측이_생기고_고객_없는_마감은_예측이_없다() {
        service.close(
                shop,
                reserve(customer, 11),
                new Closing(null, 50000, PaymentMethod.CARD, null, false),
                staff);
        service.close(
                shop,
                reserve(null, 14),
                new Closing(null, 30000, PaymentMethod.CASH, null, true),
                staff);

        assertThat(lastServiceDates()).containsExactly(Date.valueOf(DAY));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM customer_prediction WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isEqualTo(1);
    }
}

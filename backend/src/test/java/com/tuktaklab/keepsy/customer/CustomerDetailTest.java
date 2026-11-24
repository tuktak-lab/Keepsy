package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery.Detail;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery.PreferenceTag;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 고객 상세 (Keepsy #36 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. */
@SpringBootTest
class CustomerDetailTest {

    @Autowired CustomerService service;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 마스킹한_번호_주의사항_노쇼_횟수_예약_제한_취향을_담는다() {
        long customer = data.customer(shop, true, "노쇼 반복");
        jdbc.update(
                "UPDATE customer SET service_caution = '젤 리무버 주의', health_caution = '아세톤 알레르기',"
                        + " preference_memo = '조용한 시술 선호' WHERE id = ?",
                customer);
        reservation(customer, "NO_SHOW");
        reservation(customer, "NO_SHOW");
        reservation(customer, "CANCELED");
        tag(customer, "NAIL", "누드톤");
        tag(customer, "LASH", "C컬");
        tag(customer, "NAIL", "숏");

        Detail d = service.detail(shop, customer);

        assertThat(d.phone()).matches("010-\\*{4}-\\d{4}");
        assertThat(d.serviceCaution()).isEqualTo("젤 리무버 주의");
        assertThat(d.healthCaution()).isEqualTo("아세톤 알레르기");
        assertThat(d.noShowCount()).isEqualTo(2);
        assertThat(d.bookingRestricted()).isTrue();
        assertThat(d.bookingRestrictionReason()).isEqualTo("노쇼 반복");
        assertThat(d.preferenceTags())
                .containsExactly(
                        new PreferenceTag("LASH", "C컬"),
                        new PreferenceTag("NAIL", "누드톤"),
                        new PreferenceTag("NAIL", "숏"));
        assertThat(d.preferenceMemo()).isEqualTo("조용한 시술 선호");
    }

    @Test
    void 비어_있는_고객은_null과_0과_빈_배열() {
        Detail d = service.detail(shop, data.customer(shop, false, "남은 사유"));

        assertThat(d.serviceCaution()).isNull();
        assertThat(d.healthCaution()).isNull();
        assertThat(d.noShowCount()).isZero();
        assertThat(d.bookingRestricted()).isFalse();
        assertThat(d.bookingRestrictionReason()).isNull();
        assertThat(d.preferenceTags()).isEmpty();
        assertThat(d.gender()).isNull();
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found() {
        long customer = data.customer(shop, false, null);
        long otherShop = data.shop();
        assertNotFound(otherShop, customer);

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        assertNotFound(shop, customer);
    }

    private void assertNotFound(long shopId, long customerId) {
        assertThatThrownBy(() -> service.detail(shopId, customerId))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private void reservation(long customer, String status) {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 10, 0);
        jdbc.update(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, ?, 'STORE', ?)",
                shop,
                customer,
                staff,
                start,
                start.plusHours(1),
                status,
                start.minusDays(1));
    }

    private void tag(long customer, String category, String tag) {
        jdbc.update(
                "INSERT INTO preference_tag (shop_id, customer_id, category, tag) VALUES (?, ?, ?, ?)",
                shop,
                customer,
                category,
                tag);
    }
}

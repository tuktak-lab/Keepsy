package com.tuktaklab.keepsy.customer;

import static com.tuktaklab.keepsy.customer.Consent.Item.HEALTH;
import static com.tuktaklab.keepsy.customer.Consent.Item.MARKETING;
import static com.tuktaklab.keepsy.customer.Consent.Item.PRIVACY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.support.TestShopData;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 고객 등록 (Keepsy #34 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 예시 번호는 가짜 값만 쓴다. */
@SpringBootTest
class CustomerServiceTest {

    @Autowired CustomerService service;
    @Autowired CustomerRepository customers;
    @Autowired ConsentRepository consents;
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

    private static NewCustomer request(String phone, Set<Consent.Item> agreed) {
        return new NewCustomer(
                "모의 고객",
                phone,
                agreed,
                Consent.Method.TABLET,
                Customer.Gender.FEMALE,
                1995,
                Customer.PreferredLanguage.KO,
                Customer.AcquisitionChannel.INSTAGRAM);
    }

    @Test
    void 등록하면_번호를_맞추고_동의한_항목만_이력으로_남긴다() {
        long id = service.register(shop, staff, request("01000000001", Set.of(PRIVACY, MARKETING)));

        Customer c = customers.findById(id).orElseThrow();
        assertThat(c.getPhone()).isEqualTo("010-0000-0001");
        assertThat(c.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(consents.findByCustomerIdOrderByIdAsc(id))
                .extracting(
                        Consent::getItem,
                        Consent::isAgreed,
                        Consent::getStaffId,
                        Consent::getMethod)
                .containsExactlyInAnyOrder(
                        tuple(PRIVACY, true, staff, Consent.Method.TABLET),
                        tuple(MARKETING, true, staff, Consent.Method.TABLET));
    }

    @Test
    void 개인정보_동의가_없으면_고객을_만들지_않는다() {
        assertCode(
                () ->
                        service.register(
                                shop, staff, request("010-0000-0002", Set.of(MARKETING, HEALTH))),
                ErrorCode.INVALID);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM customer WHERE shop_id = ?",
                                Long.class,
                                shop))
                .isZero();
    }

    @Test
    void 같은_매장_같은_번호는_하이픈과_상관없이_중복() {
        service.register(shop, staff, request("010-0000-0003", Set.of(PRIVACY)));

        assertCode(
                () -> service.register(shop, staff, request("01000000003", Set.of(PRIVACY))),
                ErrorCode.DUPLICATE);
    }

    @Test
    void 다른_매장에는_같은_번호로_등록할_수_있다() {
        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", true);
        service.register(shop, staff, request("010-0000-0004", Set.of(PRIVACY)));

        long id =
                service.register(otherShop, otherStaff, request("010-0000-0004", Set.of(PRIVACY)));

        assertThat(customers.findById(id).orElseThrow().getShopId()).isEqualTo(otherShop);
    }

    @Test
    void CSV_전용_동의_방법과_올해_뒤_출생_연도는_거부() {
        NewCustomer ok = request("010-0000-0005", Set.of(PRIVACY));
        NewCustomer imported =
                new NewCustomer(
                        ok.name(),
                        ok.phone(),
                        ok.consents(),
                        Consent.Method.IMPORT,
                        null,
                        null,
                        null,
                        null);
        NewCustomer future =
                new NewCustomer(
                        ok.name(),
                        ok.phone(),
                        ok.consents(),
                        Consent.Method.PAPER,
                        null,
                        2999,
                        null,
                        null);

        assertCode(() -> service.register(shop, staff, imported), ErrorCode.INVALID);
        assertCode(() -> service.register(shop, staff, future), ErrorCode.INVALID);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(code);
    }
}

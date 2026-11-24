package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.customer.CustomerService.ConsentState;
import com.tuktaklab.keepsy.customer.query.ConsentHistoryQuery.Row;
import com.tuktaklab.keepsy.customer.query.ConsentHistoryQuery.StaffRef;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 동의 현재 상태 · 이력 조회 (Keepsy #47 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다.
 *
 * <pre>
 * 사진: 태블릿 동의 → 구두 직접 철회 → 서면 재동의
 * 마케팅: 태블릿 동의 → 가상 휴대폰 [수신거부] (스탭 · 방법 없음)
 * 개인정보 · 야간 광고 · 건강 정보: 받은 적 없음
 * </pre>
 */
@SpringBootTest
class ConsentHistoryTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 1, 10, 0);

    @Autowired CustomerService service;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        customer = data.customer(shop, false, null);
        consent("PHOTO", true, T, staff, "TABLET", null);
        consent("PHOTO", false, T.plusHours(1), staff, "VERBAL", "DIRECT");
        consent("PHOTO", true, T.plusHours(2), staff, "PAPER", null);
        consent("MARKETING", true, T, staff, "TABLET", null);
        consent("MARKETING", false, T.plusHours(3), null, null, "UNSUBSCRIBE");
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 동의_철회_재동의가_기록한_순서로_나오고_현재_상태는_마지막_행() {
        CustomerService.Consents result = service.consents(shop, customer);

        List<Row> photo =
                result.history().stream().filter(r -> r.item() == Consent.Item.PHOTO).toList();
        assertThat(photo).extracting(Row::agreed).containsExactly(true, false, true);
        assertThat(photo)
                .extracting(Row::method)
                .containsExactly(
                        Consent.Method.TABLET, Consent.Method.VERBAL, Consent.Method.PAPER);
        assertThat(photo.get(0).staff()).isEqualTo(new StaffRef(staff, "스탭"));

        assertThat(state(result, Consent.Item.PHOTO))
                .isEqualTo(
                        new ConsentState(
                                Consent.Item.PHOTO, true, TimeConfig.offset(T.plusHours(2))));
        assertThat(state(result, Consent.Item.MARKETING))
                .isEqualTo(
                        new ConsentState(
                                Consent.Item.MARKETING, false, TimeConfig.offset(T.plusHours(3))));
    }

    @Test
    void 철회_행에만_철회_경로가_있고_스탭_없이_들어온_철회는_스탭과_방법이_없다() {
        List<Row> history = service.consents(shop, customer).history();

        assertThat(history)
                .filteredOn(Row::agreed)
                .extracting(Row::withdrawalPath)
                .containsOnlyNulls();
        assertThat(history)
                .filteredOn(r -> !r.agreed())
                .extracting(Row::withdrawalPath)
                .containsExactly("DIRECT", "UNSUBSCRIBE");
        Row unsubscribe = history.get(history.size() - 1);
        assertThat(unsubscribe.staff()).isNull();
        assertThat(unsubscribe.method()).isNull();
    }

    @Test
    void 현재_상태는_다섯_항목이_모두_나오고_받은_적_없으면_false와_null() {
        List<ConsentState> current = service.consents(shop, customer).current();

        assertThat(current).extracting(ConsentState::item).containsExactly(Consent.Item.values());
        assertThat(current)
                .filteredOn(s ->
                        s.item() == Consent.Item.PRIVACY
                                || s.item() == Consent.Item.NIGHT_AD
                                || s.item() == Consent.Item.HEALTH)
                .allSatisfy(s -> {
                    assertThat(s.agreed()).isFalse();
                    assertThat(s.recordedAt()).isNull();
                });
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found() {
        long otherShop = data.shop();
        assertThatThrownBy(() -> service.consents(otherShop, customer))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        assertThatThrownBy(() -> service.consents(shop, customer))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private static ConsentState state(CustomerService.Consents result, Consent.Item item) {
        return result.current().stream().filter(s -> s.item() == item).findFirst().orElseThrow();
    }

    private void consent(
            String item,
            boolean agreed,
            LocalDateTime at,
            Long staffId,
            String method,
            String withdrawalPath) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id,"
                        + " method, withdrawal_path) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                shop,
                customer,
                item,
                agreed,
                at,
                staffId,
                method,
                withdrawalPath);
    }
}

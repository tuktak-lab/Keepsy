package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import com.tuktaklab.keepsy.visit.Closing;
import com.tuktaklab.keepsy.visit.PaymentMethod;
import com.tuktaklab.keepsy.visit.VisitAppService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 고객 삭제(파기) (Keepsy #50 완료 조건 · 검증 방법, C12). 실제 MySQL에서 돌고 모의 데이터만 쓴다.
 *
 * <pre>
 * 고객 c: 확정 예약 2(지난 것 · 앞으로), 완료 예약 1(메모 · 방문 · 결제), 취소 예약 1
 *        동의 3행(정정 행 포함), 상담 메모 · 취향 태그 · 사진, 현재 예측 · 스냅샷, 발송 기록
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerDeletionTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired CustomerService customerService;
    @Autowired Clock clock;
    @Autowired VisitAppService visits;
    @Autowired TransactionTemplate tx;

    private TestShopData data;
    private long shop;
    private long owner;
    private long staff;
    private long c;
    private long pastConfirmed;
    private long futureConfirmed;
    private long completed;
    private long canceled;
    private long cVisit;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        owner = data.staff(shop, "ACTIVE", true);
        staff = data.staff(shop, "ACTIVE", false);
        c = data.customer(shop, true, "모의 사유");
        jdbc.update(
                "UPDATE customer SET gender = 'FEMALE', birth_year = 1990, preferred_language = 'KO',"
                        + " acquisition_channel = 'INSTAGRAM', service_caution = '모의', health_caution = '모의',"
                        + " preference_memo = '모의' WHERE id = ?",
                c);
        LocalDateTime now = LocalDateTime.now(clock);
        pastConfirmed = reservation(now.minusDays(3), "CONFIRMED", "지난 확정 메모");
        futureConfirmed = reservation(now.plusDays(3), "CONFIRMED", "앞으로 확정 메모");
        completed = reservation(now.minusDays(10), "COMPLETED", "완료 메모");
        canceled = reservation(now.minusDays(5), "CANCELED", null);
        long visit = visit(shop, c, now.minusDays(10));
        cVisit = visit;
        visitNote(shop, visit, staff, "모의 방문 메모 1");
        visitNote(shop, visit, staff, "모의 방문 메모 2");
        jdbc.update("UPDATE reservation SET visit_id = ? WHERE id = ?", visit, completed);
        insert(
                "INSERT INTO payment (shop_id, reservation_id, visit_id, amount, method, paid_at)"
                        + " VALUES (?, ?, ?, 50000, 'CARD', ?)",
                shop,
                completed,
                visit,
                now.minusDays(10));
        insert(
                "INSERT INTO photo (shop_id, visit_id, storage_key, uploaded_at) VALUES (?, ?, ?, ?)",
                shop,
                visit,
                "test/none-" + System.nanoTime() + ".jpg",
                now);
        consent("MARKETING", true, null);
        long withdrawn = consent("MARKETING", false, null);
        consent("MARKETING", true, withdrawn); // 정정 행 → 철회 행을 가리킨다 (F44)
        consent("PRIVACY", true, null);
        insert(
                "INSERT INTO consult_note (shop_id, customer_id, staff_id, content, written_at)"
                        + " VALUES (?, ?, ?, '모의 상담', ?)",
                shop,
                c,
                staff,
                now);
        insert(
                "INSERT INTO preference_tag (shop_id, customer_id, category, tag) VALUES (?, ?, 'NAIL', '모의')",
                shop,
                c);
        insert(
                "INSERT INTO customer_prediction (shop_id, customer_id, category, last_service_date)"
                        + " VALUES (?, ?, 'NAIL', ?)",
                shop,
                c,
                now.minusDays(10).toLocalDate());
        insert(
                "INSERT INTO prediction_snapshot (shop_id, customer_id, category, created_at,"
                        + " start_date, end_date, basis_visit_count) VALUES (?, ?, 'NAIL', ?, ?, ?, 1)",
                shop,
                c,
                now,
                now.toLocalDate(),
                now.toLocalDate().plusDays(7));
        insert(
                "INSERT INTO campaign_message (shop_id, kind, customer_id, recipient_phone, message_type,"
                        + " body, send_status, result_at) VALUES (?, 'AD', ?, '010-0000-0001', 'SMS', '모의 본문',"
                        + " 'SENT', ?)",
                shop,
                c,
                now);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 삭제_전_확인은_시작_시각과_관계없이_확정_예약_수() throws Exception {
        perform(get("/api/customers/" + c + "/deletion-preview"), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelReservationCount").value(2));
    }

    @Test
    void 삭제하면_식별_정보를_비우고_관련_데이터를_정리하되_통계는_남긴다() throws Exception {
        // 지우면 안 되는 방문 메모: 같은 매장의 다른 고객, 다른 매장 고객 (F61)
        LocalDateTime now = LocalDateTime.now(clock);
        long otherCustomer = data.customer(shop, false, null);
        long otherVisit = visit(shop, otherCustomer, now.minusDays(2));
        visitNote(shop, otherVisit, staff, "다른 고객 메모");
        long otherShop = data.shop();
        long otherShopVisit =
                visit(otherShop, data.customer(otherShop, false, null), now.minusDays(2));
        visitNote(otherShop, otherShopVisit, data.staff(otherShop, "ACTIVE", true), "다른 매장 메모");

        perform(delete("/api/customers/" + c), true).andExpect(status().isNoContent());

        assertThat(count("SELECT COUNT(*) FROM visit_note WHERE visit_id = ?", cVisit)).isZero();
        assertThat(count("SELECT COUNT(*) FROM visit_note WHERE visit_id = ?", otherVisit)).isOne();
        assertThat(count("SELECT COUNT(*) FROM visit_note WHERE visit_id = ?", otherShopVisit))
                .isOne();

        Map<String, Object> row =
                new HashMap<>(
                        jdbc.queryForMap(
                                "SELECT name, phone, gender, birth_year, preferred_language,"
                                        + " service_caution, health_caution, preference_memo,"
                                        + " reservation_restrict_reason, acquisition_channel, status"
                                        + " FROM customer WHERE id = ?",
                                c));
        assertThat(row.remove("status")).isEqualTo("DELETED");
        assertThat(row.remove("acquisition_channel")).isEqualTo("INSTAGRAM");
        assertThat(row.values()).containsOnlyNulls();

        for (String table :
                List.of("consult_note", "preference_tag", "consent", "customer_prediction")) {
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE customer_id = ?", c))
                    .as(table)
                    .isZero();
        }
        assertThat(count("SELECT COUNT(*) FROM photo WHERE shop_id = ?", shop)).isZero();
        // 통계용으로 남는 것
        assertThat(count("SELECT COUNT(*) FROM prediction_snapshot WHERE customer_id = ?", c))
                .isOne();
        assertThat(count("SELECT COUNT(*) FROM visit WHERE customer_id = ?", c)).isOne();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE shop_id = ?", shop)).isOne();

        assertThat(reservationStatus(pastConfirmed)).isEqualTo("CANCELED");
        assertThat(reservationStatus(futureConfirmed)).isEqualTo("CANCELED");
        assertThat(reservationStatus(completed)).isEqualTo("COMPLETED");
        assertThat(reservationStatus(canceled)).isEqualTo("CANCELED");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM reservation WHERE customer_id = ? AND memo IS NOT NULL",
                                c))
                .isZero();

        assertThat(
                        jdbc.queryForMap(
                                "SELECT recipient_phone, body, send_status FROM campaign_message"
                                        + " WHERE customer_id = ?",
                                c))
                .containsEntry("recipient_phone", null)
                .containsEntry("body", null)
                .containsEntry("send_status", "SENT");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT staff_id, target_type, action FROM access_log"
                                        + " WHERE shop_id = ? AND target_id = ? AND action = 'DELETE'",
                                shop,
                                c))
                .containsEntry("staff_id", owner)
                .containsEntry("target_type", "CUSTOMER");

        // 이미 삭제된 고객
        perform(delete("/api/customers/" + c), true).andExpect(status().isNotFound());
        perform(get("/api/customers/" + c + "/deletion-preview"), true)
                .andExpect(status().isNotFound());
    }

    @Test
    void 삭제된_고객의_완료_예약에는_방문_메모를_더할_수_없다() throws Exception {
        perform(delete("/api/customers/" + c), true).andExpect(status().isNoContent());

        assertThatThrownBy(() -> visits.close(shop, completed, memoClosing(), staff))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID));
        assertThat(count("SELECT COUNT(*) FROM visit_note WHERE visit_id = ?", cVisit)).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment WHERE visit_id = ?", cVisit)).isOne();
    }

    /**
     * 삭제 트랜잭션이 고객 행을 잠그고 메모를 지운 채 커밋 직전에 멈춘 사이, 같은 고객의 완료 예약에 메모를 더한다. 메모 추가는 잠금에서 기다렸다가 삭제 커밋을 보고
     * 거절되어야 한다 (F61). 기다리는 것은 MySQL 접속 목록(자기 계정 것만 보임)으로 확인한다.
     */
    @Test
    void 고객_삭제와_방문_메모_추가가_겹쳐도_삭제된_고객의_메모는_남지_않는다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try {
            Future<?> deletion =
                    pool.submit(
                            asOwner(() ->
                                    tx.executeWithoutResult(s -> {
                                        customerService.delete(shop, c);
                                        deleted.countDown();
                                        awaitQuietly(commit);
                                    })));
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Long> memo =
                    pool.submit(() -> visits.close(shop, completed, memoClosing(), staff));
            awaitBlockedQuery();
            commit.countDown();
            deletion.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> memo.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID));
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM visit_note WHERE visit_id = ?", cVisit)).isZero();
        // 거절된 요청의 결제도 함께 롤백된다
        assertThat(count("SELECT COUNT(*) FROM payment WHERE visit_id = ?", cVisit)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM customer WHERE id = ?", String.class, c))
                .isEqualTo("DELETED");
    }

    @Test
    void 같은_번호로_다시_등록하면_예전_기록과_잇지_않은_새_고객() throws Exception {
        String phone =
                jdbc.queryForObject("SELECT phone FROM customer WHERE id = ?", String.class, c);
        perform(delete("/api/customers/" + c), true).andExpect(status().isNoContent());

        long again =
                customerService.register(
                        shop,
                        staff,
                        new NewCustomer(
                                "모의 고객",
                                phone,
                                Set.of(Consent.Item.PRIVACY),
                                Consent.Method.TABLET,
                                null,
                                null,
                                null,
                                null));

        assertThat(again).isNotEqualTo(c);
        assertThat(count("SELECT COUNT(*) FROM reservation WHERE customer_id = ?", again)).isZero();
    }

    @Test
    void 원장_PIN이_아니면_owner_pin_required_다른_매장_고객은_not_found() throws Exception {
        perform(delete("/api/customers/" + c), false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
        assertThat(jdbc.queryForObject("SELECT status FROM customer WHERE id = ?", String.class, c))
                .isEqualTo("ACTIVE");

        long otherCustomer = data.customer(data.shop(), false, null);
        perform(delete("/api/customers/" + otherCustomer), true).andExpect(status().isNotFound());
        perform(get("/api/customers/" + otherCustomer + "/deletion-preview"), true)
                .andExpect(status().isNotFound());
    }

    private long visit(long shopId, long customerId, LocalDateTime at) {
        return insert(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shopId,
                customerId,
                at.toLocalDate());
    }

    private void visitNote(long shopId, long visitId, long staffId, String content) {
        insert(
                "INSERT INTO visit_note (shop_id, visit_id, staff_id, content, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                shopId,
                visitId,
                staffId,
                content,
                LocalDateTime.now(clock));
    }

    private long reservation(LocalDateTime start, String status, String memo) {
        return insert(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, memo, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PHONE', ?, ?)",
                shop,
                c,
                staff,
                start,
                start.plusHours(1),
                status,
                memo,
                start.minusDays(1));
    }

    private long consent(String item, boolean agreed, Long corrects) {
        return insert(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id, method,"
                        + " withdrawal_path, corrects_consent_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                shop,
                c,
                item,
                agreed,
                LocalDateTime.now(clock),
                staff,
                agreed ? "TABLET" : null,
                agreed ? null : "DIRECT",
                corrects);
    }

    private String reservationStatus(long reservationId) {
        return jdbc.queryForObject(
                "SELECT status FROM reservation WHERE id = ?", String.class, reservationId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    PreparedStatement ps =
                            con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    return ps;
                },
                key);
        return key.getKey().longValue();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, boolean ownerPin)
            throws Exception {
        return mvc.perform(request.session(session(ownerPin)).with(csrf()));
    }

    private MockHttpSession session(boolean ownerPin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        shopSession.setPin(session, ownerPin ? owner : staff, LocalDateTime.now().plusMinutes(5));
        return session;
    }

    /** 요청 밖 스레드에서 원장 PIN 세션으로 실행한다 (접근 로그가 세션 · 요청을 읽는다) */
    private Runnable asOwner(Runnable body) {
        return () -> {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setSession(session(true));
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(
                                    shop, null, List.of()));
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                body.run();
            } finally {
                RequestContextHolder.resetRequestAttributes();
                SecurityContextHolder.clearContext();
            }
        };
    }

    private Closing memoClosing() {
        return new Closing(null, 10_000, PaymentMethod.CARD, "모의 추가 메모", false);
    }

    /** 다른 접속이 잠금 조회 · 결제 INSERT에서 기다릴 때까지 확인한다 (고정 sleep이 아니라 상태 조건) */
    private void awaitBlockedQuery() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer blocked =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.PROCESSLIST"
                                    + " WHERE ID <> CONNECTION_ID() AND COMMAND = 'Query'"
                                    + " AND (INFO LIKE '%for update%' OR INFO LIKE 'insert into payment%')",
                            Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("메모 추가가 잠금에서 기다리지 않았습니다");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

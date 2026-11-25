package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * CSV 고객 일괄 등록 (Keepsy #52 완료 조건 · 검증 방법, 결정 F4 · F5 · F50). 실제 MySQL에서 돌고 모의 이름 · 번호만 쓴다. 파일 형식 ·
 * 행 검사 자체는 {@link CustomerCsvTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerImportTest {

    private static final String HEADER = "이름,휴대폰,성별,출생연도,메모,방문일,시술카테고리\n";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired Clock clock;

    private TestShopData data;
    private long shop;
    private long staff;
    private LocalDate d1;
    private LocalDate d2;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        d1 = LocalDate.now(clock).minusDays(60);
        d2 = LocalDate.now(clock).minusDays(30);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 새_고객과_가져온_방문_상담_메모를_만들고_같은_파일을_다시_올리면_더하지_않는다() throws Exception {
        String csv =
                HEADER
                        + "모의 A,01000001001,여성,1990,\"첫 상담, 메모\",%s,네일\n".formatted(d1)
                        + "모의 A,010-0000-1001,,,\"첫 상담, 메모\",%s,속눈썹\n".formatted(d1)
                        + "모의 A,010-0000-1001,,,,%s,네일\n".formatted(d1)
                        + "모의 B,010-0000-1002,,,,,\n"
                        + "모의 C,010-0000,,,,,\n";

        upload(csv, "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCustomers").value(2))
                .andExpect(jsonPath("$.addedVisits").value(2))
                .andExpect(jsonPath("$.addedNotes").value(1))
                .andExpect(jsonPath("$.skippedDuplicates").value(1))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(6));

        long a = customerId("010-0000-1001");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT name, gender, birth_year, status FROM customer WHERE id = ?",
                                a))
                .isEqualTo(
                        Map.of(
                                "name",
                                "모의 A",
                                "gender",
                                "FEMALE",
                                "birth_year",
                                1990,
                                "status",
                                "ACTIVE"));
        // 개인정보 동의만 가져오기로, 마케팅은 행이 없어 미동의 (F19)
        assertThat(
                        jdbc.queryForList(
                                "SELECT CONCAT(item, ':', agreed, ':', method, ':', staff_id) FROM consent"
                                        + " WHERE customer_id = ?",
                                String.class,
                                a))
                .containsExactly("PRIVACY:1:IMPORT:" + staff);
        // 같은 날 다른 카테고리는 한 방문의 시술로 (C6), 메뉴 · 스탭 없이 카테고리만 (A13)
        assertThat(
                        jdbc.queryForList(
                                "SELECT CONCAT(v.source, ':', vs.category, ':', COALESCE(vs.service_menu_id, '-'),"
                                        + " ':', COALESCE(vs.staff_id, '-')) FROM visit v"
                                        + " JOIN visit_service vs ON vs.visit_id = v.id"
                                        + " WHERE v.customer_id = ? AND v.visit_date = ? ORDER BY vs.id",
                                String.class,
                                a,
                                d1))
                .containsExactly("IMPORT:NAIL:-:-", "IMPORT:LASH:-:-");
        assertThat(
                        jdbc.queryForList(
                                "SELECT CONCAT(content, ':', staff_id) FROM consult_note WHERE customer_id = ?",
                                String.class,
                                a))
                .containsExactly("첫 상담, 메모:" + staff);
        // 가져온 방문으로 첫날부터 예측
        assertThat(
                        jdbc.queryForList(
                                "SELECT CONCAT(category, ':', last_service_date) FROM customer_prediction"
                                        + " WHERE customer_id = ? ORDER BY category",
                                String.class,
                                a))
                .containsExactly("LASH:" + d1, "NAIL:" + d1);

        upload(csv, "true")
                .andExpect(jsonPath("$.createdCustomers").value(0))
                .andExpect(jsonPath("$.addedVisits").value(0))
                .andExpect(jsonPath("$.addedNotes").value(0))
                .andExpect(jsonPath("$.skippedDuplicates").value(4))
                .andExpect(jsonPath("$.errorCount").value(1));
        assertThat(count("SELECT COUNT(*) FROM customer WHERE shop_id = ?", shop)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM visit_service WHERE shop_id = ?", shop))
                .isEqualTo(2);
    }

    @Test
    void 기존_고객은_정보를_바꾸지_않고_그날_매장_방문이_있으면_방문은_건너뛴다() throws Exception {
        long existing = data.customer(shop, false, null);
        String phone =
                jdbc.queryForObject(
                        "SELECT phone FROM customer WHERE id = ?", String.class, existing);
        long menu = data.menu(shop, 60);
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                existing,
                d1);
        long normalVisit =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM visit WHERE shop_id = ?", Long.class, shop);
        jdbc.update(
                "INSERT INTO visit_service (shop_id, visit_id, service_menu_id, category) VALUES (?, ?, ?, 'NAIL')",
                shop,
                normalVisit,
                menu);
        // 삭제된 고객은 번호가 없어 같은 번호여도 새 고객 (C12)
        long deleted = data.customer(shop, false, null);
        String deletedPhone =
                jdbc.queryForObject(
                        "SELECT phone FROM customer WHERE id = ?", String.class, deleted);
        jdbc.update(
                "UPDATE customer SET status = 'DELETED', name = NULL, phone = NULL WHERE id = ?",
                deleted);

        upload(
                        HEADER
                                + "바뀐 이름,%s,남성,1980,,%s,왁싱\n".formatted(phone, d1)
                                + "바뀐 이름,%s,,,새 메모,%s,네일\n".formatted(phone, d1)
                                + "바뀐 이름,%s,,,,%s,반영구\n".formatted(phone, d2)
                                + "모의 D,%s,,,,,\n".formatted(deletedPhone),
                        "true")
                .andExpect(jsonPath("$.createdCustomers").value(1))
                .andExpect(jsonPath("$.addedVisits").value(1))
                .andExpect(jsonPath("$.addedNotes").value(1))
                .andExpect(jsonPath("$.skippedDuplicates").value(1));

        assertThat(
                        jdbc.queryForMap(
                                "SELECT name, gender, birth_year FROM customer WHERE id = ?",
                                existing))
                .containsEntry("name", "모의 고객")
                .containsEntry("gender", null)
                .containsEntry("birth_year", null);
        assertThat(count("SELECT COUNT(*) FROM consent WHERE customer_id = ?", existing)).isZero();
        // 매장 방문은 그대로
        assertThat(
                        jdbc.queryForList(
                                "SELECT category FROM visit_service WHERE visit_id = ?",
                                String.class,
                                normalVisit))
                .containsExactly("NAIL");
        assertThat(
                        jdbc.queryForList(
                                "SELECT CONCAT(v.source, ':', vs.category) FROM visit v"
                                        + " JOIN visit_service vs ON vs.visit_id = v.id"
                                        + " WHERE v.customer_id = ? AND v.visit_date = ?",
                                String.class,
                                existing,
                                d2))
                .containsExactly("IMPORT:SEMI_PERMANENT");
        assertThat(customerId(deletedPhone)).isNotEqualTo(deleted);
    }

    @Test
    void 동의_확인이_없거나_파일이_틀리면_invalid이고_아무것도_저장하지_않는다() throws Exception {
        String csv = HEADER + "모의 A,010-0000-1001,,,,,\n";
        for (String confirmed : new String[] {null, "false"}) {
            upload(csv, confirmed)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"))
                    .andExpect(jsonPath("$.error.details[0].field").value("consentConfirmed"));
        }
        upload("이름,메모\n모의,메모\n", "true")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(request().param("consentConfirmed", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        assertThat(count("SELECT COUNT(*) FROM customer WHERE shop_id = ?", shop)).isZero();
    }

    @Test
    void 데이터_3000행을_한_번에_처리한다() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 3_000; i++) {
            csv.append("모의 고객,010-1000-%04d,,,모의 메모,%s,네일\n".formatted(i, d1));
        }

        upload(csv.toString(), "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCustomers").value(3_000))
                .andExpect(jsonPath("$.addedVisits").value(3_000))
                .andExpect(jsonPath("$.addedNotes").value(3_000));
        assertThat(count("SELECT COUNT(*) FROM customer_prediction WHERE shop_id = ?", shop))
                .isEqualTo(3_000);
    }

    private long customerId(String phone) {
        return jdbc.queryForObject(
                "SELECT id FROM customer WHERE shop_id = ? AND phone = ?", Long.class, shop, phone);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private ResultActions upload(String csv, String consentConfirmed) throws Exception {
        MockMultipartHttpServletRequestBuilder r =
                request()
                        .file(
                                new MockMultipartFile(
                                        "file",
                                        "customers.csv",
                                        "text/csv",
                                        csv.getBytes(StandardCharsets.UTF_8)));
        if (consentConfirmed != null) {
            r.param("consentConfirmed", consentConfirmed);
        }
        return mvc.perform(r);
    }

    private MockMultipartHttpServletRequestBuilder request() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        MockMultipartHttpServletRequestBuilder r = multipart("/api/customers/import");
        r.session(session).with(csrf());
        return r;
    }
}

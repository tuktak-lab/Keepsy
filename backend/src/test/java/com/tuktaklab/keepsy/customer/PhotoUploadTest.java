package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
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

/**
 * 시술 사진 업로드 (Keepsy #46 완료 조건 · 검증 방법). 실제 MySQL과 테스트 저장소 디렉터리에서 돌고, 만든 파일은 끝나면 지운다. 모의 데이터만 쓴다.
 * {@code CUSTOMER_WRITE}는 기본 공용이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PhotoUploadTest {

    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'
    };
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 1, 0};

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired PhotoStorage storage;

    private TestShopData data;
    private long shop;
    private long staff;
    private long customer;
    private long visit;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        customer = data.customer(shop, false, null);
        photoConsent(customer, true);
        visit = visit(customer);
    }

    @AfterEach
    void cleanUp() throws IOException {
        for (String key :
                jdbc.queryForList(
                        "SELECT storage_key FROM photo WHERE shop_id = ?", String.class, shop)) {
            storage.delete(key);
        }
        data.cleanUp();
    }

    @Test
    void PNG_JPG를_저장소에_쓰고_방문에_붙이고_현재_근무자로_접근_로그() throws Exception {
        long png = id(upload(visit, shop, "photo.png", PNG).andExpect(status().isCreated()));
        long jpg = id(upload(visit, shop, "x.bin", JPG).andExpect(status().isCreated()));

        for (long id : List.of(png, jpg)) {
            Map<String, Object> row =
                    jdbc.queryForMap(
                            "SELECT shop_id, visit_id, storage_key FROM photo WHERE id = ?", id);
            assertThat(row).containsEntry("shop_id", shop).containsEntry("visit_id", visit);
            try (InputStream in = storage.open((String) row.get("storage_key"))) {
                assertThat(in.readAllBytes()).isEqualTo(id == png ? PNG : JPG);
            }
        }
        assertThat(
                        jdbc.queryForList(
                                "SELECT staff_id, action, target_id FROM access_log WHERE shop_id = ?",
                                shop))
                .hasSize(2)
                .allSatisfy(r ->
                        assertThat(r)
                                .isEqualTo(
                                        Map.of(
                                                "staff_id",
                                                staff,
                                                "action",
                                                "UPDATE",
                                                "target_id",
                                                customer)));
    }

    @Test
    void 사진_동의가_없거나_철회했으면_consent_required() throws Exception {
        long never = data.customer(shop, false, null);
        upload(visit(never), shop, "a.png", PNG)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("consent_required"));

        photoConsent(customer, false);
        upload(visit, shop, "a.png", PNG)
                .andExpect(jsonPath("$.error.code").value("consent_required"));
        assertThat(photoCount()).isZero();
    }

    @Test
    void GIF_빈_파일_10MB_초과_11번째_사진은_invalid() throws Exception {
        byte[] big = Arrays.copyOf(PNG, (int) PhotoUploadService.MAX_BYTES + 1);
        for (byte[] file : List.of(GIF, new byte[0], big)) {
            upload(visit, shop, "a.png", file)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"))
                    .andExpect(jsonPath("$.error.details[0].field").value("file"));
        }
        // 딱 10MB는 된다
        upload(visit, shop, "a.png", Arrays.copyOf(PNG, (int) PhotoUploadService.MAX_BYTES))
                .andExpect(status().isCreated());

        for (int i = 1; i < PhotoUploadService.MAX_PER_VISIT; i++) {
            upload(visit, shop, "a.png", PNG).andExpect(status().isCreated());
        }
        upload(visit, shop, "a.png", PNG)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("file"));
        assertThat(photoCount()).isEqualTo(PhotoUploadService.MAX_PER_VISIT);
    }

    @Test
    void 고객_없는_방문은_conflict_다른_매장_삭제된_고객_방문은_not_found() throws Exception {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, NULL, ?, 'NORMAL')",
                shop,
                LocalDate.of(2026, 11, 2));
        long walkIn =
                jdbc.queryForObject(
                        "SELECT id FROM visit WHERE shop_id = ? AND customer_id IS NULL",
                        Long.class,
                        shop);
        upload(walkIn, shop, "a.png", PNG)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));

        upload(visit, data.shop(), "a.png", PNG).andExpect(status().isNotFound());
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        upload(visit, shop, "a.png", PNG).andExpect(status().isNotFound());
        assertThat(photoCount()).isZero();
    }

    private int photoCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM photo WHERE shop_id = ?", Integer.class, shop);
    }

    private long visit(long customerId) {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customerId,
                LocalDate.of(2026, 11, 1));
        return jdbc.queryForObject(
                "SELECT id FROM visit WHERE customer_id = ?", Long.class, customerId);
    }

    private void photoConsent(long customerId, boolean agreed) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id,"
                        + " method, withdrawal_path) VALUES (?, ?, 'PHOTO', ?, ?, ?, 'TABLET', ?)",
                shop,
                customerId,
                agreed,
                LocalDateTime.now(),
                staff,
                agreed ? null : "DIRECT");
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id"))
                .longValue();
    }

    private ResultActions upload(long visitId, long shopId, String filename, byte[] content)
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return mvc.perform(
                multipart("/api/visits/{visitId}/photos", visitId)
                        .file(new MockMultipartFile("file", filename, "image/png", content))
                        .session(session)
                        .with(csrf()));
    }
}

package com.tuktaklab.keepsy.customer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.support.TestShopData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 사진 파일 서명 URL (Keepsy #13 완료 조건 「테스트: 저장 → 서명 URL로 읽기 → 만료 후 거부 → 삭제」). 실제 MySQL과 설정된 저장소 디렉터리에서
 * 돌고, 만든 파일은 끝나면 지운다. 서명 자체의 단위 테스트는 {@link PhotoStorageTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PhotoFileTest {

    /** PNG 시그니처로 시작하는 모의 바이트 (실제 이미지 아님) */
    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'
    };

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PhotoStorage storage;
    @Autowired Clock clock;

    private TestShopData data;
    private long shop;
    private long photo;
    private String key;

    @BeforeEach
    void seed() throws IOException {
        data = new TestShopData(jdbc);
        shop = data.shop();
        long customer = data.customer(shop, false, null);
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customer,
                LocalDate.of(2026, 11, 1));
        long visit =
                jdbc.queryForObject(
                        "SELECT id FROM visit WHERE customer_id = ?", Long.class, customer);
        key = storage.save(new ByteArrayInputStream(PNG));
        jdbc.update(
                "INSERT INTO photo (shop_id, visit_id, storage_key, uploaded_at) VALUES (?, ?, ?, ?)",
                shop,
                visit,
                key,
                LocalDateTime.of(2026, 11, 1, 12, 0));
        photo = jdbc.queryForObject("SELECT id FROM photo WHERE visit_id = ?", Long.class, visit);
    }

    @AfterEach
    void cleanUp() throws IOException {
        storage.delete(key);
        data.cleanUp();
    }

    @Test
    void 서명_URL로_읽으면_파일과_형식을_준다() throws Exception {
        fetch(storage.signedUrl(photo, shop, clock.instant()), shop)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(PNG));
    }

    @Test
    void 만료되거나_서명이_틀리면_not_found() throws Exception {
        String expired =
                storage.signedUrl(photo, shop, clock.instant().minus(Duration.ofMinutes(6)));
        fetch(expired, shop)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        String url = storage.signedUrl(photo, shop, clock.instant());
        fetch(url.substring(0, url.length() - 2) + "xx", shop).andExpect(status().isNotFound());
    }

    @Test
    void 다른_매장_세션이거나_다른_매장_서명이면_not_found() throws Exception {
        long otherShop = data.shop();
        // 이 매장 URL을 다른 매장 세션으로: 서명이 세션 매장과 맞지 않는다
        fetch(storage.signedUrl(photo, shop, clock.instant()), otherShop)
                .andExpect(status().isNotFound());
        // 다른 매장으로 서명한 URL: 서명은 맞지만 그 매장의 사진이 아니다
        fetch(storage.signedUrl(photo, otherShop, clock.instant()), otherShop)
                .andExpect(status().isNotFound());
    }

    @Test
    void 저장소에서_지운_파일은_not_found() throws Exception {
        storage.delete(key);

        fetch(storage.signedUrl(photo, shop, clock.instant()), shop)
                .andExpect(status().isNotFound());
    }

    @Test
    void 로그인하지_않으면_unauthorized() throws Exception {
        mvc.perform(get(storage.signedUrl(photo, shop, clock.instant())))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions fetch(String url, long sessionShop) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                sessionShop, null, List.of())));
        return mvc.perform(get(url).session(session));
    }
}

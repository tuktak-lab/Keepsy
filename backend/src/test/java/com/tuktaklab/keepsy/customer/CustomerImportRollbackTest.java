package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.customer.port.CustomerImportReferences;
import com.tuktaklab.keepsy.support.TestShopData;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 서버 오류면 파일 전체를 롤백한다 (결정 F4). 방문 단계에서 오류를 내 그 앞에 만든 고객 · 동의 · 메모도 남지 않는지 본다 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerImportRollbackTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @MockitoBean CustomerImportReferences refs;

    private TestShopData data;

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 방문_단계에서_서버_오류가_나면_아무것도_남지_않는다() throws Exception {
        data = new TestShopData(jdbc);
        long shop = data.shop();
        long staff = data.staff(shop, "ACTIVE", false);
        when(refs.importVisits(anyLong(), anyList())).thenThrow(new IllegalStateException("모의 오류"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);

        mvc.perform(
                        multipart("/api/customers/import")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "customers.csv",
                                                "text/csv",
                                                "이름,휴대폰,메모,방문일,시술카테고리\n모의,010-0000-1001,메모,2026-01-10,네일\n"
                                                        .getBytes(StandardCharsets.UTF_8)))
                                .param("consentConfirmed", "true")
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isInternalServerError());

        for (String table : List.of("customer", "consent", "consult_note")) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM " + table + " WHERE shop_id = ?",
                                    Integer.class,
                                    shop))
                    .as(table)
                    .isZero();
        }
    }
}

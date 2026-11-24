package com.tuktaklab.keepsy.common.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매장 A 세션으로 매장 B 대상을 조회하면 404 (Keepsy #5 「작업 항목」 테스트). 아직 엔티티가 없어 메모리 저장소로 규칙만 확인한다. JPA
 * Repository의 {@code findByIdAndShopId}는 첫 엔티티 PR이 같은 시나리오로 확인한다.
 */
@WebMvcTest(ShopScopeTest.TestController.class)
@Import({
    SecurityConfig.class,
    ShopSession.class,
    GlobalExceptionHandler.class,
    ShopScopeTest.TestController.class
})
class ShopScopeTest {

    /** 대상 ID → 소유 매장 ID. 매장 1의 대상 100, 매장 2의 대상 200 */
    private static final Map<Long, Long> OWNER = Map.of(100L, 1L, 200L, 2L);

    @RestController
    static class TestController {
        private final ShopSession shopSession;

        TestController(ShopSession shopSession) {
            this.shopSession = shopSession;
        }

        /** 비로그인 허용 경로. 로그인 기능(FR-ACC-02) 대신 테스트용으로 바로 로그인시킨다 */
        @PostMapping("/api/session")
        void signIn(@RequestParam long shopId, HttpServletRequest req, HttpServletResponse res) {
            shopSession.signIn(shopId, req, res);
        }

        @GetMapping("/api/things/{id}")
        String find(@PathVariable long id) {
            Long shopId = shopSession.shopId();
            Optional<Long> found =
                    Optional.ofNullable(OWNER.get(id)).filter(owner -> owner.equals(shopId));
            return "thing=" + ShopScope.orNotFound(found.map(owner -> id));
        }
    }

    @Autowired MockMvc mvc;

    private MockHttpSession signedIn(long shopId) throws Exception {
        return (MockHttpSession)
                mvc.perform(
                                post("/api/session")
                                        .param("shopId", String.valueOf(shopId))
                                        .with(csrf()))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    @Test
    void 자기_매장_대상은_조회된다() throws Exception {
        mvc.perform(get("/api/things/100").session(signedIn(1)))
                .andExpect(status().isOk())
                .andExpect(content().string("thing=100"));
    }

    @Test
    void 다른_매장_대상은_없는_것과_같은_not_found_404() throws Exception {
        MockHttpSession shopA = signedIn(1);

        mvc.perform(get("/api/things/200").session(shopA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        mvc.perform(get("/api/things/999").session(shopA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }
}

package com.tuktaklab.keepsy.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** CONTRACTS 2장 모양과 HTTP 상태 (Keepsy #1 「작업 항목」 테스트). 스프링 컨텍스트 · DB 없이 돈다. */
class GlobalExceptionHandlerTest {

    record Form(@NotBlank(message = "이름을 입력해 주세요") String name) {}

    @RestController
    static class TestController {
        @GetMapping("/api/test/not-found")
        void notFound() {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }

        @GetMapping("/api/test/conflict")
        void conflict() {
            throw new ApiException(ErrorCode.CONFLICT, "남은 예약이 있습니다", List.of(10L, 11L));
        }

        @GetMapping("/api/test/boom")
        void boom() {
            throw new IllegalStateException("내부 정보 예: SQL 문장");
        }

        @PostMapping("/api/test/form")
        void form(@Valid @RequestBody Form form) {}

        @PostMapping("/api/test/upload")
        void upload() {
            throw new MaxUploadSizeExceededException(10L * 1024 * 1024);
        }
    }

    @Test
    void 업로드_크기_한도_초과는_413이_아니라_invalid_400() throws Exception {
        mvc.perform(post("/api/test/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.message").value("파일이 너무 큽니다"));
    }

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new TestController())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

    @Test
    void 업무_예외는_표의_HTTP_상태와_code로_나가고_details는_없으면_빠진다() throws Exception {
        mvc.perform(get("/api/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.message").value("대상을 찾을 수 없습니다"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    void details는_값이_있을_때만_나간다() throws Exception {
        mvc.perform(get("/api/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"))
                .andExpect(jsonPath("$.error.message").value("남은 예약이 있습니다"))
                .andExpect(jsonPath("$.error.details[0]").value(10));
    }

    @Test
    void 처리하지_못한_예외는_internal_error_500이고_내부_정보를_내보내지_않는다() throws Exception {
        mvc.perform(get("/api/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("일시적인 오류가 발생했습니다"));
    }

    @Test
    void 입력_검증_실패는_invalid_400이고_칸별_사유를_details에_담는다() throws Exception {
        mvc.perform(
                        post("/api/test/form")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"))
                .andExpect(jsonPath("$.error.details[0].reason").value("이름을 입력해 주세요"));
    }

    @Test
    void 본문_형식이_틀리면_invalid_400() throws Exception {
        mvc.perform(post("/api/test/form").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 모든_code는_소문자_snake다() {
        for (ErrorCode code : ErrorCode.values()) {
            org.assertj.core.api.Assertions.assertThat(code.code()).matches("[a-z]+(_[a-z]+)*");
        }
    }
}

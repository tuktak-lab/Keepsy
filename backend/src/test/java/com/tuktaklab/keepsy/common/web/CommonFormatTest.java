package com.tuktaklab.keepsy.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

/** CONTRACTS 3장 형식 (Keepsy #2 「작업 항목」 테스트). 스프링 컨텍스트 · DB 없이 돈다. */
class CommonFormatTest {

    private final JsonMapper json = JsonMapper.builder().build();

    record Row(long id, LocalDate visitDate, java.time.OffsetDateTime createdAt) {}

    @Test
    void 시각은_오프셋을_붙여_내보내고_날짜는_YYYY_MM_DD() {
        Row row =
                new Row(
                        1L,
                        LocalDate.of(2026, 11, 23),
                        TimeConfig.offset(LocalDateTime.of(2026, 11, 23, 14, 30)));

        assertThat(json.writeValueAsString(row))
                .isEqualTo(
                        "{\"id\":1,\"visitDate\":\"2026-11-23\",\"createdAt\":\"2026-11-23T14:30:00+09:00\"}");
    }

    @Test
    void 페이지_응답은_items_page_size_totalElements() {
        var page = new PageImpl<>(List.of("가", "나"), PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.of(page, s -> s + "!");

        assertThat(json.writeValueAsString(response))
                .isEqualTo("{\"items\":[\"가!\",\"나!\"],\"page\":1,\"size\":2,\"totalElements\":5}");
    }

    @Test
    void 시각_변환은_null을_그대로_둔다() {
        assertThat(TimeConfig.offset(null)).isNull();
    }
}

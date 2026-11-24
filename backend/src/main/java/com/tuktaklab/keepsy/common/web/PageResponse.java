package com.tuktaklab.keepsy.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 목록 API 공통 페이지 응답 (CONTRACTS 3장): {@code { "items": [...], "page": 0, "size": 20, "totalElements":
 * 123 }}. 요청은 컨트롤러에서 {@code Pageable}로 받는다 ({@code ?page=0&size=20}, 기본 20건).
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {

    /** 엔티티 페이지를 DTO로 바꿔 담는다 (엔티티를 응답으로 바로 내보내지 않음, DEVELOPMENT 4장) */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> toDto) {
        return new PageResponse<>(
                page.getContent().stream().map(toDto).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }
}

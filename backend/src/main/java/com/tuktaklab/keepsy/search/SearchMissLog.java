package com.tuktaklab.keepsy.search;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 결과 없는 검색어 (명세서 5장 search_miss_log, FR-SCH-06). 기능 · 고객 결과가 둘 다 없을 때만 남긴다 (F28) */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchMissLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private String keyword;

    private LocalDateTime searchedAt;

    static SearchMissLog of(long shopId, String keyword, LocalDateTime searchedAt) {
        SearchMissLog log = new SearchMissLog();
        log.shopId = shopId;
        log.keyword = keyword;
        log.searchedAt = searchedAt;
        return log;
    }
}

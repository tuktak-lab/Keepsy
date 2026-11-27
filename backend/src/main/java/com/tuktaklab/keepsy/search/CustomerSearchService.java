package com.tuktaklab.keepsy.search;

import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.search.query.CustomerSearchQuery;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고객 통합 검색 (FR-SCH-04)과 결과 없는 검색어 저장 (FR-SCH-06). 검색어는 연락처일 수 있어 로그에 남기지 않는다 (NFR-06) */
@Service
@RequiredArgsConstructor
public class CustomerSearchService {

    private final CustomerSearchQuery query;
    private final SearchMissLogRepository misses;
    private final Clock clock;

    /**
     * @param q 비어 있지 않은 검색어 (컨트롤러가 검증)
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerSearchQuery.Item> customers(
            long shopId, String q, int page, int size) {
        String term = q.strip();
        return new PageResponse<>(
                query.find(shopId, term, page, size), page, size, query.count(shopId, term));
    }

    /**
     * 화면이 기능 결과가 없을 때 부른다. 고객 결과가 하나라도 있으면 저장하지 않는다 (F28). "고객 조회"가 PIN으로 잠겨 화면이 고객 결과를 모를 때도 여기서
     * 거른다
     *
     * @param keyword 비어 있지 않은 100자 이하 검색어 (컨트롤러가 검증)
     */
    @Transactional
    public void recordMiss(long shopId, String keyword) {
        String term = keyword.strip();
        if (query.count(shopId, term) > 0) {
            return;
        }
        misses.save(SearchMissLog.of(shopId, term, LocalDateTime.now(clock)));
    }
}

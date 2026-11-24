package com.tuktaklab.keepsy.search;

import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.search.query.CustomerSearchQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고객 통합 검색 (FR-SCH-04). 검색어는 연락처일 수 있어 로그에 남기지 않는다 (NFR-06) */
@Service
@RequiredArgsConstructor
public class CustomerSearchService {

    private final CustomerSearchQuery query;

    /**
     * @param q 비어 있지 않은 검색어 (컨트롤러가 검증)
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerSearchQuery.Item> customers(
            long shopId, String q, int page, int size) {
        String term = q.strip();
        // ponytail: 접근 로그(NFR-06)는 기록 수단(#12)이 머지되면 개인정보 조회로 남길지 정해 여기서 남긴다
        return new PageResponse<>(
                query.find(shopId, term, page, size), page, size, query.count(shopId, term));
    }
}

package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.customer.query.ConsultNoteQuery;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 상담 메모 (FR-CUS-06). 권한은 컨트롤러에서 검사한다 */
@Service
@RequiredArgsConstructor
public class ConsultNoteService {

    private final CustomerRepository customerRepository;
    private final ConsultNoteRepository noteRepository;
    private final ConsultNoteQuery noteQuery;
    private final Clock clock;

    /**
     * @param staffId 작성 스탭 = 현재 근무자 (FR-ACC-10, D6)
     * @return 만든 메모 ID
     * @throws ApiException 다른 매장 · 삭제된 고객이면 {@code not_found}
     */
    @Transactional
    public long write(long shopId, long customerId, long staffId, String content) {
        requireCustomer(shopId, customerId);
        return noteRepository
                .save(
                        ConsultNote.write(
                                shopId, customerId, staffId, content, LocalDateTime.now(clock)))
                .getId();
    }

    /**
     * 최신 작성순 목록.
     *
     * @throws ApiException 다른 매장 · 삭제된 고객이면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public PageResponse<ConsultNoteQuery.Row> list(
            long shopId, long customerId, int page, int size) {
        requireCustomer(shopId, customerId);
        return new PageResponse<>(
                noteQuery.find(shopId, customerId, page, size),
                page,
                size,
                noteQuery.count(shopId, customerId));
    }

    private void requireCustomer(long shopId, long customerId) {
        if (!customerRepository.existsByIdAndShopIdAndStatus(
                customerId, shopId, Customer.Status.ACTIVE)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}

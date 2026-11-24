package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.common.audit.AccessLogger.Action;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.FeatureKey;
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
    private final AccessLogger accessLogger;
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

    /**
     * 내용 수정 (C5). 접근 로그를 같은 트랜잭션에 남긴다 (NFR-06).
     *
     * @throws ApiException 다른 매장 · 없는 메모면 {@code not_found}
     */
    @Transactional
    public void edit(long shopId, long noteId, String content) {
        ConsultNote note = find(shopId, noteId);
        note.edit(content);
        log(note, Action.UPDATE);
    }

    /**
     * 삭제 (C5). 접근 로그를 같은 트랜잭션에 남긴다 (NFR-06).
     *
     * @throws ApiException 다른 매장 · 없는 메모면 {@code not_found}
     */
    @Transactional
    public void delete(long shopId, long noteId) {
        ConsultNote note = find(shopId, noteId);
        noteRepository.delete(note);
        log(note, Action.DELETE);
    }

    private ConsultNote find(long shopId, long noteId) {
        return noteRepository
                .findByIdAndShopId(noteId, shopId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private void log(ConsultNote note, Action action) {
        accessLogger.record(
                FeatureKey.CUSTOMER_EDIT, AccessLogger.CUSTOMER, note.getCustomerId(), action);
    }

    private void requireCustomer(long shopId, long customerId) {
        if (!customerRepository.existsByIdAndShopIdAndStatus(
                customerId, shopId, Customer.Status.ACTIVE)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}

package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.common.audit.AccessLogger.Action;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.customer.query.ConsultNoteQuery;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 상담 메모 API (CONTRACTS 4 · 6장 「고객 (CUS)」) */
@RestController
@RequiredArgsConstructor
class ConsultNoteController {

    private final ConsultNoteService service;
    private final ShopSession shopSession;
    private final AccessLogger accessLogger;

    record WriteRequest(
            @NotBlank(message = "메모를 입력해 주세요") @Size(max = 2000, message = "메모는 2,000자 이하입니다")
                    String content) {}

    record WriteResponse(long noteId) {}

    @RequireFeature(FeatureKey.CUSTOMER_EDIT)
    @PatchMapping("/api/consult-notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void edit(@PathVariable long noteId, @Valid @RequestBody WriteRequest r) {
        service.edit(shopSession.shopId(), noteId, r.content());
    }

    @RequireFeature(FeatureKey.CUSTOMER_EDIT)
    @DeleteMapping("/api/consult-notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long noteId) {
        service.delete(shopSession.shopId(), noteId);
    }

    @RequireFeature(FeatureKey.CUSTOMER_WRITE)
    @PostMapping("/api/customers/{customerId}/consult-notes")
    @ResponseStatus(HttpStatus.CREATED)
    WriteResponse write(
            @PathVariable long customerId,
            @Valid @RequestBody WriteRequest r,
            HttpSession session) {
        return new WriteResponse(
                service.write(
                        shopSession.shopId(),
                        customerId,
                        shopSession.currentStaffId(session),
                        r.content()));
    }

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/customers/{customerId}/consult-notes")
    PageResponse<ConsultNoteQuery.Row> list(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageResponse<ConsultNoteQuery.Row> notes =
                service.list(shopSession.shopId(), customerId, page, size);
        accessLogger.record(
                FeatureKey.CUSTOMER_VIEW, AccessLogger.CUSTOMER, customerId, Action.VIEW);
        return notes;
    }
}

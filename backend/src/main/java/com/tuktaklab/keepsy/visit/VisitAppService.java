package com.tuktaklab.keepsy.visit;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.visit.Visit.ActualService;
import com.tuktaklab.keepsy.visit.port.VisitReferences;
import com.tuktaklab.keepsy.visit.port.VisitReferences.ReservationToClose;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 완료로 방문을 만드는 규칙 (FR-VIS-01·02, A13, C6)과 시술 마감 카드 저장 (FR-PAY-01·04, E3, E4). 결제 · 방문 생성 · 방문 메모
 * · 예약 완료를 한 트랜잭션에서 처리한다 (ARCHITECTURE 4장, 점검표 D-7의 의식적 예외).
 */
@Service
@RequiredArgsConstructor
public class VisitAppService {

    private final VisitRepository visits;
    private final PaymentRepository payments;
    private final VisitNoteRepository notes;
    private final VisitReferences refs;
    private final Clock clock;

    /**
     * {@code POST /api/reservations/{id}/complete}: 결제 없이 완료 처리 (FR-VIS-01).
     *
     * @param actualMenuIds 확인한 실제 시술. null이면 예정 시술을 그대로 쓴다. [상담 후 진행] 예약은 넘겨야 한다
     * @return 방문 ID
     */
    @Transactional
    public long completeWithoutPayment(long shopId, long reservationId, List<Long> actualMenuIds) {
        ReservationToClose r = refs.lockReservation(shopId, reservationId);
        if (!r.confirmed()) {
            throw new ApiException(ErrorCode.CONFLICT, "확정 예약만 완료할 수 있습니다");
        }
        if (r.customerId() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "고객 없는 예약은 시술 마감 카드에서 마감해 주세요");
        }
        List<Closing.Item> items =
                actualMenuIds == null
                        ? null
                        : actualMenuIds.stream()
                                .map(id -> new Closing.Item(id, r.staffId(), false))
                                .toList();
        return completeWithVisit(shopId, r, items).getId();
    }

    /**
     * {@code POST /api/reservations/{id}/closing}: 시술 마감 카드 저장 (FR-PAY-04). 확정 예약이면 방문을 만들고 완료하고,
     * 이미 완료된 예약이면 같은 방문에 결제 · 메모만 더한다 (E4).
     *
     * @param actorStaffId 현재 근무자 (메모 작성 스탭, FR-ACC-10)
     * @return 방문 ID. 화면은 이 ID로 사진을 따로 올린다 (F9)
     */
    @Transactional
    public long close(long shopId, long reservationId, Closing in, long actorStaffId) {
        if (in.method() == null || in.amount() < 0) {
            throw new ApiException(ErrorCode.INVALID, "금액과 결제 수단을 입력해 주세요");
        }
        ReservationToClose r = refs.lockReservation(shopId, reservationId);
        boolean hasMemo = in.memo() != null && !in.memo().isBlank();
        Visit visit;
        if (r.confirmed()) {
            if (r.customerId() == null && !in.noCustomer()) {
                throw new ApiException(ErrorCode.CONFLICT, "고객을 먼저 연결하거나 고객 없음으로 마감해 주세요");
            }
            if (r.customerId() != null && in.noCustomer()) {
                throw new ApiException(ErrorCode.INVALID, "고객이 연결된 예약은 고객 없음으로 마감할 수 없습니다");
            }
            if (in.noCustomer() && (hasMemo || anyCycleExcluded(in.services()))) {
                throw new ApiException(ErrorCode.INVALID, "고객 없음으로 마감하면 메모와 주기 계산 제외를 남길 수 없습니다");
            }
            visit = completeWithVisit(shopId, r, in.services());
        } else if (r.completed()) {
            // 완료된 예약: 결제 · 메모만 더하고 실제 시술은 읽기 전용 (E4, 시술 수정은 FR-VIS-03)
            if (in.services() != null) {
                throw new ApiException(ErrorCode.INVALID, "완료된 예약의 실제 시술은 방문 수정에서 바꿔 주세요");
            }
            visit = visits.findById(r.visitId()).orElseThrow();
            if (visit.getCustomerId() == null && hasMemo) {
                throw new ApiException(ErrorCode.INVALID, "고객 없음으로 마감한 방문에는 메모를 남길 수 없습니다");
            }
        } else {
            throw new ApiException(ErrorCode.CONFLICT, "취소 · 노쇼 예약은 마감할 수 없습니다");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        payments.save(
                Payment.of(
                        shopId,
                        visit.getCustomerId(),
                        r.id(),
                        visit.getId(),
                        in.amount(),
                        in.method(),
                        now));
        if (hasMemo) {
            notes.save(VisitNote.of(shopId, visit.getId(), actorStaffId, in.memo(), now));
        }
        // 예측 재계산은 방문을 만드는 completeWithVisit에서 한다 (완료된 예약은 결제 · 메모만 더해 방문이 그대로다)
        // ponytail: 고객 마지막 방문일(FR-CUS-12) · 귀속(FR-VIS-04)은 그 서비스가 생기면 여기서 부른다
        return visit.getId();
    }

    /** {@code PATCH /api/payments/{id}}: 금액 · 결제 수단 수정 (FR-PAY-02). 취소된 결제는 {@code conflict} */
    @Transactional
    public void updatePayment(long shopId, long paymentId, int amount, PaymentMethod method) {
        findPayment(shopId, paymentId).update(amount, method);
    }

    /** {@code POST /api/payments/{id}/cancel}: 결제만 취소하고 방문은 둔다 (FR-PAY-02, F8) */
    @Transactional
    public void cancelPayment(long shopId, long paymentId) {
        findPayment(shopId, paymentId).cancel();
    }

    /** CONTRACTS 6장 「고객 (CUS)」 결제 금액 이력 항목 */
    public record PaymentItem(
            long paymentId,
            OffsetDateTime paidAt,
            int amount,
            PaymentMethod method,
            boolean canceled,
            long reservationId,
            Long visitId) {}

    /**
     * {@code GET /api/customers/{id}/payments}: 고객 결제 금액 이력 (FR-ANL-04). 취소된 결제도 이력이라 빼지 않고 표시한다
     * (명세서 5장 payment, F8). 다른 매장 · 삭제된 고객은 not_found
     */
    @Transactional(readOnly = true)
    public PageResponse<PaymentItem> customerPayments(
            long shopId, long customerId, int page, int size) {
        if (!refs.isActiveCustomer(shopId, customerId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return PageResponse.of(
                payments.findByShopIdAndCustomerIdOrderByPaidAtDescIdDesc(
                        shopId, customerId, PageRequest.of(page, size)),
                p ->
                        new PaymentItem(
                                p.getId(),
                                TimeConfig.offset(p.getPaidAt()),
                                p.getAmount(),
                                p.getMethod(),
                                p.isCanceled(),
                                p.getReservationId(),
                                p.getVisitId()));
    }

    /** 방문 삭제 전에 결제 권한(PAYMENT_EDIT)도 검사해야 하는지: 아직 취소되지 않은 결제가 있으면 true (CONTRACTS 4장) */
    @Transactional(readOnly = true)
    public boolean hasActivePayments(long shopId, long visitId) {
        return payments.findByShopIdAndVisitId(shopId, visitId).stream()
                .anyMatch(p -> !p.isCanceled());
    }

    /**
     * {@code DELETE /api/visits/{id}}: 방문 삭제 = 그 방문의 마감 되돌리기 (FR-VIS-03, F8). 한 트랜잭션에서 연결된 예약을 모두
     * 확정으로, 결제를 모두 취소 표시 · 방문 연결 해제(이력 보존), 시술 · 방문 메모 · 사진 행과 방문을 지운다. 사진 파일은 커밋 뒤에 지운다.
     */
    @Transactional
    public void deleteVisit(long shopId, long visitId) {
        Visit visit =
                visits.findForUpdateByIdAndShopId(visitId, shopId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (visit.getCustomerId() == null) {
            // 되돌리면 다시 마감할 때 고객을 연결할 수 있어 "고객 없음 방문은 나중에 고객과 연결하지 않음"(E3)을 우회한다
            throw new ApiException(ErrorCode.CONFLICT, "고객 없음으로 마감한 방문은 삭제할 수 없습니다");
        }
        refs.reopenReservations(shopId, visitId);
        payments.findByShopIdAndVisitId(shopId, visitId).forEach(Payment::detachFromDeletedVisit);
        payments.flush();
        notes.deleteVisitNotes(shopId, visitId);
        refs.deletePhotos(shopId, visitId);
        visits.delete(visit);
        visits.flush();
        // 지운 방문을 빼고 다시 계산한다. 카테고리 방문이 남지 않으면 그 예측 행이 지워진다 (FR-PRD-01)
        refs.recalculatePrediction(shopId, visit.getCustomerId());
        // ponytail: 고객 마지막 방문일(FR-CUS-12)은 그 서비스가 생기면 여기서 부른다. 스냅샷 재평가 · 귀속 승계는 하지 않는다 (#71)
    }

    private Payment findPayment(long shopId, long paymentId) {
        return payments.findByIdAndShopId(paymentId, shopId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** 확정 예약 하나를 방문에 붙이고 완료로 바꾼다. 고객이 있으면 같은 날 방문에 합치고, 없으면 예약마다 새 방문 (C6, E3) */
    private Visit completeWithVisit(long shopId, ReservationToClose r, List<Closing.Item> items) {
        if (items != null && items.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID, "실제 시술을 1개 이상 골라 주세요");
        }
        List<Closing.Item> actual =
                items != null
                        ? items
                        : r.plannedMenuIds().stream()
                                .map(id -> new Closing.Item(id, r.staffId(), false))
                                .toList();
        Set<Long> menuIds =
                new HashSet<>(actual.stream().map(Closing.Item::serviceMenuId).toList());
        Set<Long> staffIds = new HashSet<>(actual.stream().map(Closing.Item::staffId).toList());
        Map<Long, String> categories = refs.menuCategories(shopId, menuIds);
        if (!categories.keySet().containsAll(menuIds)
                || !refs.shopStaffIds(shopId, staffIds).containsAll(staffIds)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }

        Visit visit;
        if (r.customerId() == null) {
            visit = visits.save(Visit.fromReservation(shopId, null, r.date()));
        } else {
            // 같은 고객 · 같은 날 방문 1건 (C6). 동시에 두 예약이 완료돼도 방문이 하나만 생기게 고객 행을 잠근다
            refs.lockCustomer(shopId, r.customerId());
            visit =
                    visits.findByShopIdAndCustomerIdAndVisitDate(shopId, r.customerId(), r.date())
                            .orElseGet(() ->
                                    visits.save(
                                            Visit.fromReservation(
                                                    shopId, r.customerId(), r.date())));
        }
        visit.addReservationServices(
                r.id(),
                actual.stream()
                        .map(i ->
                                new ActualService(
                                        i.serviceMenuId(),
                                        categories.get(i.serviceMenuId()),
                                        i.staffId(),
                                        i.cycleExcluded()))
                        .toList());
        visits.flush();
        refs.completeReservation(shopId, r.id(), visit.getId(), items != null);
        if (visit.getCustomerId() != null) {
            // 같은 트랜잭션에서 예측을 다시 계산한다. 고객 없는 방문은 예측이 없다 (FR-PRD-01, E3)
            refs.recalculatePrediction(shopId, visit.getCustomerId());
        }
        return visit;
    }

    private static boolean anyCycleExcluded(List<Closing.Item> items) {
        return items != null && items.stream().anyMatch(Closing.Item::cycleExcluded);
    }
}

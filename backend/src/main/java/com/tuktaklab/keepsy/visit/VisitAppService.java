package com.tuktaklab.keepsy.visit;

import com.tuktaklab.keepsy.analytics.Attribution;
import com.tuktaklab.keepsy.analytics.Attribution.SentAd;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.visit.Visit.ActualService;
import com.tuktaklab.keepsy.visit.port.VisitReferences;
import com.tuktaklab.keepsy.visit.port.VisitReferences.ReservationToClose;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        ReservationToClose r = lockForClosing(shopId, reservationId);
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
        ReservationToClose r = lockForClosing(shopId, reservationId);
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
        // 귀속(FR-VIS-04)은 방문을 만드는 completeWithVisit에서 한다
        // ponytail: 고객 마지막 방문일(FR-CUS-12)은 그 서비스가 생기면 여기서 부른다
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

    /**
     * {@code PATCH /api/visits/{id}}: 방문의 실제 시술 · 담당 스탭 · 주기 계산 제외 수정 (FR-VIS-03 · 05, F25). 고객이
     * 있으면 같은 트랜잭션에서 예측을 다시 계산한다. "앞으로 이 메뉴를 모두 제외할까요?" 안내는 화면이 메뉴 예측 속성으로 띄운다 (CONTRACTS 6장)
     */
    @Transactional
    public void editVisit(long shopId, long visitId, List<Closing.Item> items) {
        if (items == null || items.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID, "실제 시술을 1개 이상 골라 주세요");
        }
        Visit visit =
                visits.findForUpdateByIdAndShopId(visitId, shopId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (visit.getSource() == VisitSource.IMPORT) {
            // 메뉴 · 스탭 없이 카테고리만 있는 방문. 가져오기(FR-CUS-13)를 구현할 때 다시 정한다 (F25)
            throw new ApiException(ErrorCode.CONFLICT, "가져온 방문은 수정할 수 없습니다");
        }
        if (visit.getCustomerId() == null && anyCycleExcluded(items)) {
            throw new ApiException(ErrorCode.INVALID, "고객 없음으로 마감한 방문에는 주기 계산 제외를 남길 수 없습니다");
        }
        visit.replaceServices(
                toActualServices(shopId, items), refs.earliestReservationId(shopId, visitId));
        visits.flush();
        if (visit.getCustomerId() != null) {
            refs.recalculatePrediction(shopId, visit.getCustomerId());
        }
    }

    private Payment findPayment(long shopId, long paymentId) {
        return payments.findByIdAndShopId(paymentId, shopId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * 마감 잠금 (#336). 순서는 고객 → 예약 → 방문이고, 모든 잠금이 이 트랜잭션의 첫 일반 조회보다 먼저다. 그래서 같은 고객의 앞선 마감이 커밋한 방문 ·
     * 예측을 스냅샷이 본다. 고객 삭제(고객 → 예약)와도 순서가 같아 교착하지 않는다. 고객 ID는 잠그기 전에 따로 읽으므로, 잠근 뒤 다르면(그사이 고객 연결)
     * conflict.
     */
    private ReservationToClose lockForClosing(long shopId, long reservationId) {
        Long customerId = refs.reservationCustomerId(shopId, reservationId);
        if (customerId != null) {
            refs.lockCustomer(shopId, customerId);
        }
        ReservationToClose r = refs.lockReservation(shopId, reservationId);
        if (!Objects.equals(customerId, r.customerId())) {
            throw new ApiException(ErrorCode.CONFLICT, "예약 정보가 바뀌었습니다. 다시 시도해 주세요");
        }
        return r;
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
        List<ActualService> actualServices = toActualServices(shopId, actual);

        Visit visit;
        if (r.customerId() == null) {
            visit = visits.save(Visit.fromReservation(shopId, null, r.date()));
        } else {
            // 같은 고객 · 같은 날 방문 1건 (C6). 고객 행은 lockForClosing에서 이미 잠갔다
            visit =
                    visits.findByShopIdAndCustomerIdAndVisitDate(shopId, r.customerId(), r.date())
                            .orElseGet(() ->
                                    visits.save(
                                            Visit.fromReservation(
                                                    shopId, r.customerId(), r.date())));
        }
        if (visit.addReservationServices(r.id(), actualServices)) {
            attribute(shopId, visit, r);
        }
        visits.flush();
        refs.completeReservation(shopId, r.id(), visit.getId(), items != null);
        if (visit.getCustomerId() != null) {
            // 같은 트랜잭션에서 예측을 다시 계산한다. 고객 없는 방문은 예측이 없다 (FR-PRD-01, E3)
            refs.recalculatePrediction(shopId, visit.getCustomerId());
        }
        return visit;
    }

    /**
     * 예약 하나로 더한 실제 시술마다 캠페인 귀속을 저장한다 (FR-VIS-04, A8, E7, F33). 예약의 같은 카테고리 시술이 귀속돼 있으면 그 발송을
     * 이어받고(방문일이 귀속 기간 밖이어도), 아니면 예약 시작 시각 기준으로 따로 계산한다. 다른 방문 시술이 이미 가진 발송은 첫 방문이 아니라서 연결하지 않는다. 고객
     * 없는 방문은 발송이 없고, 가져온 방문은 귀속에서 뺀다 (FR-CUS-13: 같은 날 가져온 방문에 합쳐진 경우).
     */
    private void attribute(long shopId, Visit visit, ReservationToClose r) {
        if (visit.getCustomerId() == null || visit.getSource() == VisitSource.IMPORT) {
            return;
        }
        List<SentAd> sends = refs.sentAds(shopId, visit.getCustomerId());
        if (sends.isEmpty()) {
            return;
        }
        int days = refs.attributionDays(shopId);
        Set<Long> claimed =
                new HashSet<>(
                        visits.findClaimedMessageIds(
                                shopId,
                                visit.getId(),
                                sends.stream().map(SentAd::messageId).toList()));
        visit.getServices().stream()
                .filter(s -> Long.valueOf(r.id()).equals(s.getReservationId()))
                .forEach(s ->
                        Attribution.attributeVisit(
                                        s.getCategory(),
                                        r.attributedMessageIds(),
                                        r.startAt(),
                                        sends,
                                        claimed,
                                        days)
                                .ifPresent(s::attribute));
    }

    /** 그 매장의 메뉴 · 스탭인지 확인하고 메뉴의 시술 카테고리를 붙인다. 다른 매장 것이면 not_found (NFR-03) */
    private List<ActualService> toActualServices(long shopId, List<Closing.Item> items) {
        Set<Long> menuIds = new HashSet<>(items.stream().map(Closing.Item::serviceMenuId).toList());
        Set<Long> staffIds = new HashSet<>(items.stream().map(Closing.Item::staffId).toList());
        Map<Long, String> categories = refs.menuCategories(shopId, menuIds);
        if (!categories.keySet().containsAll(menuIds)
                || !refs.shopStaffIds(shopId, staffIds).containsAll(staffIds)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return items.stream()
                .map(i ->
                        new ActualService(
                                i.serviceMenuId(),
                                categories.get(i.serviceMenuId()),
                                i.staffId(),
                                i.cycleExcluded()))
                .toList();
    }

    /** CSV 한 행의 방문 (FR-CUS-13) */
    public record ImportedVisit(long customerId, LocalDate visitDate, String category) {}

    /**
     * CSV로 가져온 과거 방문을 더한다 (FR-CUS-13, C6, 결정 F50). 그날 방문이 없으면 {@code IMPORT} 방문과 메뉴 없는 시술을 만들고, 그날
     * 가져온 방문이 있으면 새 카테고리만 시술을 더하고, 그날 매장 방문이 있으면 건드리지 않는다. 더해진 고객만 예측을 다시 계산한다. 부르는 쪽 트랜잭션에서 함께
     * 커밋된다.
     *
     * @param items 다른 매장 고객이 아님은 부르는 쪽이 확인한다
     * @return 항목마다 방문 · 시술을 더했는지, 같은 순서
     */
    @Transactional
    public List<Boolean> importVisits(long shopId, List<ImportedVisit> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<String, Visit> byDay = new HashMap<>();
        for (Visit v :
                visits.findWithServices(
                        shopId,
                        items.stream().map(ImportedVisit::customerId).distinct().toList())) {
            byDay.put(v.getCustomerId() + ":" + v.getVisitDate(), v);
        }
        List<Boolean> added = new ArrayList<>(items.size());
        Set<Long> changed = new HashSet<>();
        for (ImportedVisit i : items) {
            Visit visit =
                    byDay.computeIfAbsent(
                            i.customerId() + ":" + i.visitDate(),
                            k ->
                                    visits.save(
                                            Visit.imported(shopId, i.customerId(), i.visitDate())));
            boolean add = visit.addImportedService(i.category());
            added.add(add);
            if (add) {
                changed.add(i.customerId());
            }
        }
        // 예측 재계산은 JDBC로 방문을 읽으므로 먼저 내보낸다
        visits.flush();
        refs.recalculatePredictions(shopId, changed);
        return added;
    }

    private static boolean anyCycleExcluded(List<Closing.Item> items) {
        return items != null && items.stream().anyMatch(Closing.Item::cycleExcluded);
    }
}

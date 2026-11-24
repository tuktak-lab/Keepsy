package com.tuktaklab.keepsy.visit;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.visit.Visit.ActualService;
import com.tuktaklab.keepsy.visit.port.VisitReferences;
import com.tuktaklab.keepsy.visit.port.VisitReferences.ReservationToClose;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 예약 완료로 방문을 만드는 규칙 (FR-VIS-01·02, A13, C6). 시술 마감 카드(FR-PAY-04)도 같은 규칙을 쓴다. */
@Service
@RequiredArgsConstructor
public class VisitAppService {

    private final VisitRepository visits;
    private final VisitReferences refs;

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
        if (actualMenuIds != null && actualMenuIds.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID, "실제 시술을 1개 이상 골라 주세요");
        }
        List<Long> menuIds = actualMenuIds != null ? actualMenuIds : r.plannedMenuIds();
        Map<Long, String> categories = refs.menuCategories(shopId, new HashSet<>(menuIds));
        if (!categories.keySet().containsAll(menuIds)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }

        // 같은 고객 · 같은 날 방문 1건 (C6). 동시에 두 예약이 완료돼도 방문이 하나만 생기게 고객 행을 잠근다
        refs.lockCustomer(shopId, r.customerId());
        Visit visit =
                visits.findByShopIdAndCustomerIdAndVisitDate(shopId, r.customerId(), r.date())
                        .orElseGet(() ->
                                visits.save(
                                        Visit.fromReservation(shopId, r.customerId(), r.date())));
        visit.addReservationServices(
                r.id(),
                r.staffId(),
                menuIds.stream().map(id -> new ActualService(id, categories.get(id))).toList());
        visits.flush();

        refs.completeReservation(shopId, r.id(), visit.getId(), actualMenuIds != null);
        // ponytail: 예측 재계산(FR-PRD-01) · 고객 마지막 방문일(FR-CUS-12) 갱신은 그 서비스가 생기면 여기서 부른다
        return visit.getId();
    }
}

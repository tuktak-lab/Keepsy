package com.tuktaklab.keepsy.reservation;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.reservation.AvailableTimes.Booking;
import com.tuktaklab.keepsy.reservation.port.ReservationReferences;
import com.tuktaklab.keepsy.reservation.port.ReservationReferences.CustomerState;
import com.tuktaklab.keepsy.reservation.port.ReservationReferences.MenuSummary;
import com.tuktaklab.keepsy.reservation.port.ReservationReferences.StaffState;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 예약 가능 시간 조회와 예약 등록 (FR-RSV-01·04). 매장은 호출하는 쪽이 세션에서 넘긴다 (NFR-03). */
@Service
@RequiredArgsConstructor
public class ReservationAppService {

    private final ReservationRepository reservations;
    private final ReservationReferences refs;
    private final Clock clock;

    /** {@code GET /api/reservations/available-times} (FR-RSV-04) */
    @Transactional(readOnly = true)
    public List<LocalTime> availableTimes(long shopId, long staffId, LocalDate date, int minutes) {
        if (minutes <= 0 || minutes % AvailableTimes.SLOT_MINUTES != 0) {
            throw new ApiException(ErrorCode.INVALID, "소요 시간은 30분 단위로 입력해 주세요");
        }
        requireStaff(refs.staff(shopId, staffId));
        return AvailableTimes.startTimes(
                refs.openingHours(shopId, date),
                refs.isDayOff(shopId, staffId, date),
                bookings(shopId, staffId, date, null),
                minutes);
    }

    /**
     * {@code POST /api/reservations} (FR-RSV-01). 담당 스탭 행을 먼저 잠그고 저장 직전에 겹침을 다시 검사한다 (FR-RSV-04,
     * C7).
     *
     * @param ownerPinVerified 원장 PIN 인증이 있는 요청인지. 예약 제한 고객이면 필요하다 (FR-CUS-14)
     * @return 새 예약 ID
     */
    @Transactional
    public long create(long shopId, NewReservation in, boolean ownerPinVerified) {
        // 잠금이 트랜잭션의 첫 조회여야 한다. MySQL REPEATABLE READ는 첫 일반 SELECT에서 스냅샷을 잡으므로, 잠금보다 앞에 조회가 있으면
        // 먼저 저장된 같은 시간 예약을 못 보고 겹쳐 저장할 수 있다
        requireStaff(refs.lockStaff(shopId, in.staffId()));
        List<Long> menuIds = in.serviceMenuIds() == null ? List.of() : in.serviceMenuIds();
        Map<Long, Integer> durations = refs.menuDurations(shopId, new HashSet<>(menuIds));
        if (!durations.keySet().containsAll(menuIds)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        if (in.customerId() != null) {
            CustomerState customer =
                    refs.customer(shopId, in.customerId())
                            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (customer.restricted() && !ownerPinVerified) {
                throw new ApiException(
                        ErrorCode.OWNER_PIN_REQUIRED,
                        "예약 제한 고객입니다. 사유: " + customer.restrictReason());
            }
        }
        int minutes =
                in.minutes() != null
                        ? in.minutes()
                        : menuIds.stream().mapToInt(durations::get).sum();
        // 입력 규칙(예정 시술 1개 이상, 30분 단위, 고객 없는 예약 메모)은 엔티티가 검사한다
        Reservation reservation =
                Reservation.create(
                        shopId,
                        in.customerId(),
                        in.staffId(),
                        in.startAt(),
                        minutes,
                        in.channel(),
                        in.consultFirst(),
                        in.memo(),
                        menuIds,
                        LocalDateTime.now(clock));

        LocalDate date = in.startAt().toLocalDate();
        boolean available =
                AvailableTimes.isAvailable(
                        refs.openingHours(shopId, date),
                        refs.isDayOff(shopId, in.staffId(), date),
                        bookings(shopId, in.staffId(), date, null),
                        in.startAt().toLocalTime(),
                        minutes);
        if (!available) {
            throw new ApiException(ErrorCode.RESERVATION_CONFLICT);
        }
        return reservations.save(reservation).getId();
    }

    /**
     * {@code PATCH /api/reservations/{id}} (FR-RSV-01, F22). 확정 예약만, 등록과 같은 입력 규칙 · 겹침 규칙이고 자기 자신과의
     * 겹침은 보지 않는다. 고객은 바꾸지 않는다 ({@code in.customerId()}는 쓰지 않음).
     */
    @Transactional
    public void update(long shopId, long reservationId, NewReservation in) {
        // 등록과 같은 이유로 담당 스탭 잠금이 첫 조회다. 스탭을 바꾸지 않아도 같은 스탭의 동시 등록 · 수정과 줄을 세운다
        requireStaff(refs.lockStaff(shopId, in.staffId()));
        Reservation reservation =
                reservations
                        .findForUpdateByIdAndShopId(reservationId, shopId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        List<Long> menuIds = in.serviceMenuIds() == null ? List.of() : in.serviceMenuIds();
        Map<Long, Integer> durations = refs.menuDurations(shopId, new HashSet<>(menuIds));
        if (!durations.keySet().containsAll(menuIds)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        int minutes =
                in.minutes() != null
                        ? in.minutes()
                        : menuIds.stream().mapToInt(durations::get).sum();
        reservation.update(
                in.staffId(),
                in.startAt(),
                minutes,
                in.channel(),
                in.consultFirst(),
                in.memo(),
                menuIds);

        LocalDate date = in.startAt().toLocalDate();
        boolean available =
                AvailableTimes.isAvailable(
                        refs.openingHours(shopId, date),
                        refs.isDayOff(shopId, in.staffId(), date),
                        bookings(shopId, in.staffId(), date, reservationId),
                        in.startAt().toLocalTime(),
                        minutes);
        if (!available) {
            throw new ApiException(ErrorCode.RESERVATION_CONFLICT);
        }
    }

    /** {@code POST /api/reservations/{id}/cancel}. 확정 예약만 (FR-RSV-03) */
    @Transactional
    public void cancel(long shopId, long reservationId) {
        find(shopId, reservationId).cancel();
    }

    /** {@code POST /api/reservations/{id}/no-show}. 시작 시각이 지난 확정 예약만 (C8). 고객 없는 예약도 된다 (E3) */
    @Transactional
    public void markNoShow(long shopId, long reservationId) {
        find(shopId, reservationId).markNoShow(LocalDateTime.now(clock));
    }

    /**
     * {@code PUT /api/reservations/{id}/customer}. 고객 없는 확정 예약에 고객을 연결한다 (FR-RSV-01, FR-DSH-01). 예약
     * 제한 고객이어도 PIN 없이 연결하고 안내만 돌려준다 (FR-CUS-14, E3).
     *
     * @return 예약 제한 고객이면 "예약 제한 고객입니다. 사유: …" 안내
     */
    @Transactional
    public Optional<String> linkCustomer(long shopId, long reservationId, long customerId) {
        Reservation reservation = find(shopId, reservationId);
        CustomerState customer =
                refs.customer(shopId, customerId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        reservation.linkCustomer(customerId);
        return customer.restricted()
                ? Optional.of("예약 제한 고객입니다. 사유: " + customer.restrictReason())
                : Optional.empty();
    }

    /**
     * {@code GET /api/reservations/{id}} 응답 (CONTRACTS 6장 「예약 (RSV)」). 고객 없는 예약이면 customer가 null
     * (E3)
     */
    public record ReservationDetail(
            long id,
            ReservationStatus status,
            OffsetDateTime startAt,
            int minutes,
            ReservationChannel channel,
            boolean consultFirst,
            String memo,
            StaffView staff,
            List<ServiceView> services,
            CustomerView customer,
            Long visitId) {}

    public record StaffView(long id, String name) {}

    public record ServiceView(long serviceMenuId, String name, int price) {}

    public record CustomerView(long id, String name, String serviceCaution, String healthCaution) {}

    /** {@code GET /api/reservations/{id}}. 상태와 관계없이 조회한다 (FR-RSV-01, FR-CUS-05, FR-DSH-01) */
    @Transactional(readOnly = true)
    public ReservationDetail detail(long shopId, long reservationId) {
        Reservation r = find(shopId, reservationId);
        List<Long> menuIds =
                r.getServices().stream()
                        .sorted(Comparator.comparing(ReservationService::getId))
                        .map(ReservationService::getServiceMenuId)
                        .toList();
        Map<Long, MenuSummary> menus = refs.menuSummaries(shopId, menuIds);
        // ponytail: 주의사항 조회 접근 로그(NFR-06)는 access_log(#11 · #12)가 생기면 여기서 남긴다
        CustomerView customer =
                r.getCustomerId() == null
                        ? null
                        : refs.customerSummary(shopId, r.getCustomerId())
                                .map(c ->
                                        new CustomerView(
                                                r.getCustomerId(),
                                                c.name(),
                                                c.serviceCaution(),
                                                c.healthCaution()))
                                .orElse(null);
        return new ReservationDetail(
                r.getId(),
                r.getStatus(),
                TimeConfig.offset(r.getStartAt()),
                (int) Duration.between(r.getStartAt(), r.getEndAt()).toMinutes(),
                r.getChannel(),
                r.isConsultFirst(),
                r.getMemo(),
                new StaffView(r.getStaffId(), refs.staffName(shopId, r.getStaffId())),
                menuIds.stream()
                        .map(id -> new ServiceView(id, menus.get(id).name(), menus.get(id).price()))
                        .toList(),
                customer,
                r.getVisitId());
    }

    /** 시술 마감 · 완료 처리가 읽는 예약 값 (visit 도메인이 Port로 부른다, DEVELOPMENT 4장 「도메인 연동」) */
    public record ClosingTarget(
            long id,
            Long customerId,
            long staffId,
            LocalDate date,
            ReservationStatus status,
            boolean consultFirst,
            boolean servicesConfirmed,
            Long visitId,
            List<Long> plannedMenuIds) {}

    /** 예약을 행 잠금으로 읽는다. 같은 예약의 완료 · 마감이 동시에 들어와도 한 번만 처리된다 */
    @Transactional
    public ClosingTarget lockForClosing(long shopId, long reservationId) {
        Reservation r =
                reservations
                        .findForUpdateByIdAndShopId(reservationId, shopId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return new ClosingTarget(
                r.getId(),
                r.getCustomerId(),
                r.getStaffId(),
                r.getStartAt().toLocalDate(),
                r.getStatus(),
                r.isConsultFirst(),
                r.isServicesConfirmed(),
                r.getVisitId(),
                r.getServices().stream().map(ReservationService::getServiceMenuId).toList());
    }

    /** 확정 → 완료, 방문 연결 (FR-VIS-01). 방문을 만든 같은 트랜잭션에서 부른다 */
    @Transactional
    public void complete(long shopId, long reservationId, long visitId, boolean confirmServices) {
        find(shopId, reservationId).complete(visitId, confirmServices);
    }

    /** 방문 삭제(마감 되돌리기): 그 방문에 연결된 예약을 모두 확정으로 되돌린다 (FR-VIS-03, F8). visit 도메인이 Port로 부른다 */
    @Transactional
    public void reopenForVisit(long shopId, long visitId) {
        reservations.findByShopIdAndVisitId(shopId, visitId).forEach(Reservation::reopen);
        reservations.flush();
    }

    /** 세션 매장의 예약. 없거나 다른 매장 것이면 똑같이 404 (NFR-03) */
    private Reservation find(long shopId, long reservationId) {
        return reservations
                .findByIdAndShopId(reservationId, shopId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** 그 매장 스탭이 아니면 404, 비활성이면 담당으로 지정할 수 없다 (FR-ACC-04) */
    private static void requireStaff(Optional<StaffState> staff) {
        if (!staff.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).active()) {
            throw new ApiException(ErrorCode.INVALID, "비활성 스탭은 담당으로 지정할 수 없습니다");
        }
    }

    /**
     * @param excludeId 수정 중인 예약. 자기 자신과의 겹침은 보지 않는다 (null이면 모두)
     */
    private List<Booking> bookings(long shopId, long staffId, LocalDate date, Long excludeId) {
        return reservations
                .findStaffReservations(
                        shopId, staffId, date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                .stream()
                .filter(r -> !r.getId().equals(excludeId))
                .map(r ->
                        new Booking(
                                r.getStartAt().toLocalTime(),
                                r.getEndAt().toLocalTime(),
                                r.getStatus()))
                .toList();
    }
}

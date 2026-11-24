package com.tuktaklab.keepsy.reservation;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.reservation.AvailableTimes.Booking;
import com.tuktaklab.keepsy.reservation.port.ReservationReferences;
import com.tuktaklab.keepsy.reservation.port.ReservationReferences.CustomerState;
import com.tuktaklab.keepsy.reservation.port.ReservationReferences.StaffState;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
                bookings(shopId, staffId, date),
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
                        bookings(shopId, in.staffId(), date),
                        in.startAt().toLocalTime(),
                        minutes);
        if (!available) {
            throw new ApiException(ErrorCode.RESERVATION_CONFLICT);
        }
        return reservations.save(reservation).getId();
    }

    /** 그 매장 스탭이 아니면 404, 비활성이면 담당으로 지정할 수 없다 (FR-ACC-04) */
    private static void requireStaff(Optional<StaffState> staff) {
        if (!staff.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).active()) {
            throw new ApiException(ErrorCode.INVALID, "비활성 스탭은 담당으로 지정할 수 없습니다");
        }
    }

    private List<Booking> bookings(long shopId, long staffId, LocalDate date) {
        return reservations
                .findStaffReservations(
                        shopId, staffId, date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                .stream()
                .map(r ->
                        new Booking(
                                r.getStartAt().toLocalTime(),
                                r.getEndAt().toLocalTime(),
                                r.getStatus()))
                .toList();
    }
}

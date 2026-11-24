package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.port.StaffReservations;
import com.tuktaklab.keepsy.account.port.StaffReservations.Booked;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.ShopScope;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 스탭 등록 · 수정 (FR-ACC-03, FR-SET-07). 권한(원장 PIN)은 Controller 앞에서 검사한다 */
@Service
@RequiredArgsConstructor
public class StaffCommandService {

    private final ShopRepository shopRepository;
    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;
    private final StaffReservations reservations;
    private final Clock clock;

    public record NewStaff(
            String name,
            String pin,
            boolean lockedFeatureAllowed,
            List<DayOfWeek> regularDaysOff) {}

    /** null인 칸은 바꾸지 않는다 */
    public record StaffChanges(
            String name, Boolean lockedFeatureAllowed, List<DayOfWeek> regularDaysOff) {}

    /**
     * @return 만든 스탭 ID
     * @throws ApiException 같은 매장 스탭(비활성 포함)과 PIN이 같으면 {@code duplicate}, 요일이 겹치면 {@code invalid}
     */
    @Transactional
    public long create(long shopId, NewStaff in) {
        requireDistinct(in.regularDaysOff());
        requireUniquePin(shopId, in.pin(), null);
        Staff staff =
                Staff.member(
                        shopId,
                        in.name(),
                        passwordEncoder.encode(in.pin()),
                        in.lockedFeatureAllowed(),
                        in.regularDaysOff());
        return staffRepository.save(staff).getId();
    }

    /**
     * @return 정기 휴무 요일을 바꿨다면 바꾼 뒤 정기 휴무 요일에 걸리는 확정 예약(지금 이후). 자동 취소하지 않는다 (F12)
     * @throws ApiException 다른 매장 · 없는 스탭이면 {@code not_found}, 원장 허용을 끄거나 요일이 겹치면 {@code invalid}
     */
    @Transactional
    public List<Booked> update(long shopId, long staffId, StaffChanges changes) {
        Staff staff = ShopScope.orNotFound(staffRepository.findByIdAndShopId(staffId, shopId));
        if (changes.name() != null) {
            staff.rename(changes.name());
        }
        if (changes.lockedFeatureAllowed() != null) {
            staff.allowLockedFeatures(changes.lockedFeatureAllowed());
        }
        if (changes.regularDaysOff() == null) {
            return List.of();
        }
        requireDistinct(changes.regularDaysOff());
        staff.changeDaysOff(changes.regularDaysOff());
        return reservations.confirmedOn(shopId, staffId, staff.daysOff(), LocalDateTime.now(clock));
    }

    /**
     * 스탭 PIN 재설정 (FR-ACC-03). 그 스탭의 세션(근무자 · PIN 인증)은 그대로 두고 다음 PIN 입력부터 새 PIN을 쓴다.
     *
     * @throws ApiException 다른 매장 · 없는 스탭이면 {@code not_found}, 원장 스탭이면 {@code invalid}, 같은 매장 다른
     *     스탭(비활성 포함)과 PIN이 같으면 {@code duplicate}
     */
    @Transactional
    public void resetPin(long shopId, long staffId, String pin) {
        Staff staff = ShopScope.orNotFound(staffRepository.findByIdAndShopId(staffId, shopId));
        staff.resetPin(passwordEncoder.encode(pin));
        // 중복이면 예외로 트랜잭션 전체가 롤백되어 위 변경도 남지 않는다
        requireUniquePin(shopId, pin, staffId);
    }

    /** 비활성화를 막는 예약 하나 ({@code conflict}의 details, CONTRACTS 6장) */
    public record BlockingReservation(long id, OffsetDateTime startAt, String customerName) {}

    /**
     * 스탭 비활성화 (FR-ACC-04, F34). 이미 비활성이면 그대로 둔다. 그 스탭의 세션은 권한 검사가 다음 요청부터 막는다.
     *
     * @throws ApiException 다른 매장 · 없는 스탭이면 {@code not_found}, 원장 스탭이면 {@code invalid}, 확정 예약(지난 ·
     *     앞으로, 고객 없는 예약 포함)이 남아 있으면 {@code conflict}이고 details에 그 예약 목록
     */
    @Transactional
    public void deactivate(long shopId, long staffId) {
        Staff staff = ShopScope.orNotFound(staffRepository.findForUpdate(staffId, shopId));
        if (staff.getStatus() == StaffStatus.INACTIVE) {
            return;
        }
        List<Booked> remaining = reservations.allConfirmed(shopId, staffId);
        if (!remaining.isEmpty()) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "남은 확정 예약을 다른 스탭으로 옮기거나 마감 · 취소한 뒤 비활성화해 주세요",
                    remaining.stream()
                            .map(b ->
                                    new BlockingReservation(
                                            b.id(),
                                            TimeConfig.offset(b.startAt()),
                                            b.customerName()))
                            .toList());
        }
        staff.deactivate();
    }

    /**
     * PIN은 해시라 DB 고유 제약을 걸 수 없어 같은 매장 스탭(비활성 포함)의 해시와 하나씩 비교한다 (ARCHITECTURE 5장 「PIN 중복 검사」). 같은
     * PIN을 동시에 등록 · 재설정해도 하나만 통과하도록 매장 행을 잠근 뒤 비교한다 (PIN 인증과 같은 방식, F14).
     *
     * @param exceptStaffId 재설정하는 스탭 자신. 자기 지금 PIN과 같은 것은 허용한다
     */
    private void requireUniquePin(long shopId, String pin, Long exceptStaffId) {
        ShopScope.orNotFound(shopRepository.findForUpdateById(shopId));
        for (Staff s : staffRepository.findByShopIdOrderByStatusAscOwnerDescIdAsc(shopId)) {
            if (!s.getId().equals(exceptStaffId) && passwordEncoder.matches(pin, s.getPinHash())) {
                throw new ApiException(ErrorCode.DUPLICATE, "같은 매장에 같은 PIN을 쓰는 스탭이 있습니다");
            }
        }
    }

    private static void requireDistinct(List<DayOfWeek> days) {
        if (new HashSet<>(days).size() != days.size()) {
            throw new ApiException(ErrorCode.INVALID, "정기 휴무 요일이 겹칩니다");
        }
    }
}

package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.port.StaffReservations;
import com.tuktaklab.keepsy.account.port.StaffReservations.Booked;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.ShopScope;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
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
        // 같은 PIN을 동시에 등록해도 하나만 통과하도록 매장 행을 잠근다 (PIN 인증과 같은 방식, F14)
        ShopScope.orNotFound(shopRepository.findForUpdateById(shopId));
        // PIN은 해시라 DB 고유 제약을 걸 수 없어 하나씩 비교한다 (ARCHITECTURE 5장 「PIN 중복 검사」)
        for (Staff s : staffRepository.findByShopIdOrderByStatusAscOwnerDescIdAsc(shopId)) {
            if (passwordEncoder.matches(in.pin(), s.getPinHash())) {
                throw new ApiException(ErrorCode.DUPLICATE, "같은 매장에 같은 PIN을 쓰는 스탭이 있습니다");
            }
        }
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

    private static void requireDistinct(List<DayOfWeek> days) {
        if (new HashSet<>(days).size() != days.size()) {
            throw new ApiException(ErrorCode.INVALID, "정기 휴무 요일이 겹칩니다");
        }
    }
}

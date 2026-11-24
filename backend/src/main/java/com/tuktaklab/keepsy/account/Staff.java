package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 스탭 (명세서 5장 staff, A3: 원장 · 직원 구분 없이 모두 스탭) */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Staff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private String name;

    /** BCrypt. 매장 안 중복은 해시라 서버에서 비교 검사 (명세서 5장 비고) */
    private String pinHash;

    /** 원장 스탭 (FR-ACC-01) */
    private boolean owner;

    /** 잠긴 기능 사용 허용. 원장은 항상 켜져 있고 끌 수 없다 (FR-ACC-01) */
    private boolean lockedFeatureAllowed;

    /** 정기 휴무 요일. 쉼표로 이은 MONDAY..SUNDAY, 없으면 빈 문자열 (FR-SET-07). 읽을 때는 {@link #daysOff()} */
    private String regularDaysOff;

    @Enumerated(EnumType.STRING)
    private StaffStatus status;

    /** 정기 휴무 요일, 월요일부터 순서 (CONTRACTS 3장 「요일」, F20) */
    public List<DayOfWeek> daysOff() {
        if (regularDaysOff == null || regularDaysOff.isBlank()) {
            return List.of();
        }
        return Arrays.stream(regularDaysOff.split(","))
                .map(d -> DayOfWeek.valueOf(d.trim()))
                .sorted()
                .toList();
    }

    /** 원장 PIN으로 등록하는 스탭 (FR-ACC-03). 원장은 가입 때만 생긴다 */
    static Staff member(
            long shopId,
            String name,
            String pinHash,
            boolean lockedFeatureAllowed,
            List<DayOfWeek> regularDaysOff) {
        Staff staff = new Staff();
        staff.shopId = shopId;
        staff.name = name;
        staff.pinHash = pinHash;
        staff.lockedFeatureAllowed = lockedFeatureAllowed;
        staff.changeDaysOff(regularDaysOff);
        staff.status = StaffStatus.ACTIVE;
        return staff;
    }

    void rename(String name) {
        this.name = name;
    }

    /**
     * @throws ApiException 원장 스탭이면 {@code invalid}. 원장 PIN은 매장 비밀번호를 다시 확인하는 원장 PIN
     *     재설정(FR-ACC-09)으로만 바꾼다
     */
    void resetPin(String pinHash) {
        if (owner) {
            throw new ApiException(
                    ErrorCode.INVALID, "원장 PIN은 원장 PIN 재설정에서 매장 비밀번호를 확인한 뒤 바꿀 수 있습니다");
        }
        this.pinHash = pinHash;
    }

    /**
     * @throws ApiException 원장 스탭의 허용을 끄면 {@code invalid} (FR-ACC-01)
     */
    void allowLockedFeatures(boolean allowed) {
        if (owner && !allowed) {
            throw new ApiException(ErrorCode.INVALID, "원장 스탭의 잠긴 기능 사용 허용은 끌 수 없습니다");
        }
        this.lockedFeatureAllowed = allowed;
    }

    /**
     * 비활성화 (FR-ACC-04). 작성한 기록은 ID로 남아 있으므로 지우지 않는다. 남은 예약 검사는 서비스가 한다 (F34).
     *
     * @throws ApiException 원장 스탭이면 {@code invalid} (FR-ACC-01)
     */
    void deactivate() {
        if (owner) {
            throw new ApiException(ErrorCode.INVALID, "원장 스탭은 비활성화할 수 없습니다");
        }
        this.status = StaffStatus.INACTIVE;
    }

    /** 정기 휴무 요일 전체를 바꾼다. 월요일부터 순서로 저장한다 */
    void changeDaysOff(List<DayOfWeek> days) {
        this.regularDaysOff =
                days.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
    }

    /** 가입 때 만드는 원장 스탭 (FR-ACC-01) */
    static Staff owner(long shopId, String name, String pinHash) {
        Staff staff = new Staff();
        staff.shopId = shopId;
        staff.name = name;
        staff.pinHash = pinHash;
        staff.owner = true;
        staff.lockedFeatureAllowed = true;
        // 매핑한 칸은 INSERT에 모두 들어가 DB 기본값('')이 쓰이지 않는다
        staff.regularDaysOff = "";
        staff.status = StaffStatus.ACTIVE;
        return staff;
    }
}

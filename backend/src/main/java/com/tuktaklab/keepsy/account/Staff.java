package com.tuktaklab.keepsy.account;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 스탭 (명세서 5장 staff, A3: 원장 · 직원 구분 없이 모두 스탭). 정기 휴무 요일은 그 기능(FR-SET-07)을 구현할 때 매핑한다. */
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

    @Enumerated(EnumType.STRING)
    private StaffStatus status;

    /** 가입 때 만드는 원장 스탭 (FR-ACC-01) */
    static Staff owner(long shopId, String name, String pinHash) {
        Staff staff = new Staff();
        staff.shopId = shopId;
        staff.name = name;
        staff.pinHash = pinHash;
        staff.owner = true;
        staff.lockedFeatureAllowed = true;
        staff.status = StaffStatus.ACTIVE;
        return staff;
    }
}

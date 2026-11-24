package com.tuktaklab.keepsy.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 매장 · 스탭 임시 휴무일 (명세서 5장 day_off, FR-SET-07). 등록 · 삭제(#32)는 이 엔티티로 한다 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DayOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    /** 스탭 휴무면 스탭 ID, 매장 휴무면 null. 스탭은 account 도메인이라 ID로만 참조한다 */
    private Long staffId;

    private LocalDate offDate;

    private String reason;
}

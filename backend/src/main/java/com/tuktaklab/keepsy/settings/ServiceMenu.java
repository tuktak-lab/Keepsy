package com.tuktaklab.keepsy.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 시술 메뉴 (명세서 5장 service_menu, FR-SET-02). 추가 · 수정(#29)은 이 엔티티 메서드로 한다 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    /** 시술 카테고리 NAIL | LASH | WAXING | SEMI_PERMANENT | ETC (CONTRACTS 3장) */
    private String category;

    private String name;

    /** 원 */
    private int price;

    /** 30분 단위 (FR-SET-02) */
    private int durationMinutes;

    private int cycleMinDays;

    private int cycleMaxDays;

    /** 메뉴 예측 속성 NORMAL | EXCLUDE | END_SIGNAL (E5) */
    private String predictionAttribute;
}

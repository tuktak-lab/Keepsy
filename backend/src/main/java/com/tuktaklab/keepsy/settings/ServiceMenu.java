package com.tuktaklab.keepsy.settings;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler.FieldViolation;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 시술 메뉴 (명세서 5장 service_menu, FR-SET-02). 카테고리는 만든 뒤 바꾸지 않는다 (F31) */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceMenu {

    /** 소요 시간 단위(분) (FR-SET-02) */
    static final int DURATION_UNIT = 30;

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

    /** 칸 하나의 형식(빈 이름 · 음수 등)은 요청에서 검사하고, 여기서는 30분 단위와 최소 ≤ 최대를 검사한다 */
    static ServiceMenu create(
            long shopId,
            String category,
            String name,
            int price,
            int durationMinutes,
            int cycleMinDays,
            int cycleMaxDays,
            String predictionAttribute) {
        requireValid(durationMinutes, cycleMinDays, cycleMaxDays);
        ServiceMenu menu = new ServiceMenu();
        menu.shopId = shopId;
        menu.category = category;
        menu.name = name.strip();
        menu.price = price;
        menu.durationMinutes = durationMinutes;
        menu.cycleMinDays = cycleMinDays;
        menu.cycleMaxDays = cycleMaxDays;
        menu.predictionAttribute = predictionAttribute;
        return menu;
    }

    /**
     * null인 칸은 그대로 둔다 (F31).
     *
     * @return 기본 주기나 예측 속성이 바뀌었으면 true (예측 재계산 대상, FR-SET-02)
     */
    boolean change(
            String name,
            Integer price,
            Integer durationMinutes,
            Integer cycleMinDays,
            Integer cycleMaxDays,
            String predictionAttribute) {
        int newDuration = Objects.requireNonNullElse(durationMinutes, this.durationMinutes);
        int newMin = Objects.requireNonNullElse(cycleMinDays, this.cycleMinDays);
        int newMax = Objects.requireNonNullElse(cycleMaxDays, this.cycleMaxDays);
        String newAttribute =
                Objects.requireNonNullElse(predictionAttribute, this.predictionAttribute);
        requireValid(newDuration, newMin, newMax);
        boolean predictionChanged =
                newMin != this.cycleMinDays
                        || newMax != this.cycleMaxDays
                        || !newAttribute.equals(this.predictionAttribute);
        if (name != null) {
            this.name = name.strip();
        }
        this.price = Objects.requireNonNullElse(price, this.price);
        this.durationMinutes = newDuration;
        this.cycleMinDays = newMin;
        this.cycleMaxDays = newMax;
        this.predictionAttribute = newAttribute;
        return predictionChanged;
    }

    private static void requireValid(int durationMinutes, int cycleMinDays, int cycleMaxDays) {
        if (durationMinutes % DURATION_UNIT != 0) {
            throw invalid("durationMinutes", "소요 시간은 30분 단위입니다");
        }
        if (cycleMinDays > cycleMaxDays) {
            throw invalid("cycleMinDays", "기본 주기 최소가 최대보다 클 수 없습니다");
        }
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(
                ErrorCode.INVALID, reason, List.of(new FieldViolation(field, reason)));
    }
}

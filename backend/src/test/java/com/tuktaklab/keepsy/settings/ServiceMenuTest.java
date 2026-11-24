package com.tuktaklab.keepsy.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

/** 시술 메뉴 규칙 (Keepsy #29, FR-SET-02, F31). DB 없이 돈다 */
class ServiceMenuTest {

    private static ServiceMenu nail() {
        return ServiceMenu.create(1L, "NAIL", "  젤 원컬러 ", 50000, 90, 21, 28, "NORMAL");
    }

    private static void assertInvalid(Runnable r) {
        assertThatThrownBy(r::run)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID);
    }

    @Test
    void 추가는_이름_앞뒤_공백을_빼고_30분_단위와_최소_최대를_검사한다() {
        assertThat(nail().getName()).isEqualTo("젤 원컬러");
        assertInvalid(() -> ServiceMenu.create(1L, "NAIL", "젤", 0, 45, 21, 28, "NORMAL"));
        assertInvalid(() -> ServiceMenu.create(1L, "NAIL", "젤", 0, 60, 29, 28, "NORMAL"));
    }

    @Test
    void 주기나_예측_속성이_바뀔_때만_재계산_대상이다() {
        ServiceMenu menu = nail();
        assertThat(menu.change("젤 그라데이션", 60000, 120, null, null, null)).isFalse();
        assertThat(menu.change(null, null, null, 21, 28, "NORMAL")).isFalse();
        assertThat(menu.change(null, null, null, null, 35, null)).isTrue();
        assertThat(menu.change(null, null, null, null, null, "EXCLUDE")).isTrue();
        assertThat(menu.getName()).isEqualTo("젤 그라데이션");
        assertThat(menu.getPrice()).isEqualTo(60000);
        assertThat(menu.getDurationMinutes()).isEqualTo(120);
        assertThat(menu.getCycleMinDays()).isEqualTo(21);
        assertThat(menu.getCycleMaxDays()).isEqualTo(35);
        assertThat(menu.getPredictionAttribute()).isEqualTo("EXCLUDE");
        assertThat(menu.getCategory()).isEqualTo("NAIL");
    }

    @Test
    void 수정은_바꾼_뒤_값으로_검사하고_어기면_아무것도_바꾸지_않는다() {
        ServiceMenu menu = nail();
        // 최대만 보내도 지금 최소(21)와 비교한다
        assertInvalid(() -> menu.change("새 이름", null, null, null, 20, null));
        assertInvalid(() -> menu.change(null, null, 45, null, null, null));
        assertThat(menu.getName()).isEqualTo("젤 원컬러");
        assertThat(menu.getCycleMaxDays()).isEqualTo(28);
        assertThat(menu.getDurationMinutes()).isEqualTo(90);
    }
}

package com.tuktaklab.keepsy.account;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 스탭 엔티티 규칙 (FR-ACC-01 · 03, FR-SET-07). DB 없이 돈다. */
class StaffTest {

    @Test
    void 등록한_스탭은_활성이고_정기_휴무는_월요일부터_순서로_저장된다() {
        Staff staff = Staff.member(1L, "모의 스탭", "hash", false, List.of(FRIDAY, MONDAY));

        assertThat(staff.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(staff.isOwner()).isFalse();
        assertThat(staff.getRegularDaysOff()).isEqualTo("MONDAY,FRIDAY");
        assertThat(staff.daysOff()).containsExactly(MONDAY, FRIDAY);

        staff.changeDaysOff(List.of());
        assertThat(staff.getRegularDaysOff()).isEmpty();
        assertThat(staff.daysOff()).isEmpty();
    }

    @Test
    void 원장의_잠긴_기능_허용은_끌_수_없고_직원은_켜고_끌_수_있다() {
        Staff owner = Staff.owner(1L, "원장", "hash");
        assertThatThrownBy(() -> owner.allowLockedFeatures(false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID);
        assertThat(owner.isLockedFeatureAllowed()).isTrue();

        Staff staff = Staff.member(1L, "모의 스탭", "hash", false, List.of());
        staff.allowLockedFeatures(true);
        assertThat(staff.isLockedFeatureAllowed()).isTrue();
        staff.allowLockedFeatures(false);
        assertThat(staff.isLockedFeatureAllowed()).isFalse();
    }

    @Test
    void 직원_PIN은_재설정되고_원장_PIN은_여기서_바꿀_수_없다() {
        Staff staff = Staff.member(1L, "모의 스탭", "old", false, List.of());
        staff.resetPin("new");
        assertThat(staff.getPinHash()).isEqualTo("new");

        Staff owner = Staff.owner(1L, "원장", "old");
        assertThatThrownBy(() -> owner.resetPin("new"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID);
        assertThat(owner.getPinHash()).isEqualTo("old");
    }
}

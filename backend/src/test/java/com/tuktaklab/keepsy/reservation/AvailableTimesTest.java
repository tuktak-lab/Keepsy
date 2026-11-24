package com.tuktaklab.keepsy.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.reservation.AvailableTimes.Booking;
import com.tuktaklab.keepsy.reservation.AvailableTimes.OpeningHours;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** FR-RSV-04, C7. 케이스는 Keepsy #54 「완료 조건」을 따른다. */
class AvailableTimesTest {

    /** 10:00~19:00 영업 */
    private static final Optional<OpeningHours> HOURS =
            Optional.of(new OpeningHours(LocalTime.of(10, 0), LocalTime.of(19, 0)));

    private static LocalTime t(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    private static Booking booking(LocalTime start, LocalTime end, ReservationStatus status) {
        return new Booking(start, end, status);
    }

    @Test
    void 시작부터_종료까지_전체가_영업시간_안인_30분_단위_시작_시각만_나온다() {
        List<LocalTime> times = AvailableTimes.startTimes(HOURS, false, List.of(), 120);

        assertThat(times).first().isEqualTo(t(10, 0));
        assertThat(times).last().isEqualTo(t(17, 0)); // 19:00 마감이면 2시간 시술은 17:00까지 (C7 예시)
        assertThat(times).hasSize(15);
        assertThat(times).allMatch(time -> time.getMinute() % 30 == 0);
    }

    @Test
    void 확정_완료_예약과_겹치는_시작_시각은_나오지_않는다() {
        List<Booking> bookings =
                List.of(
                        booking(t(12, 0), t(13, 0), ReservationStatus.CONFIRMED),
                        booking(t(15, 0), t(16, 0), ReservationStatus.COMPLETED));

        List<LocalTime> times = AvailableTimes.startTimes(HOURS, false, bookings, 60);

        assertThat(times).doesNotContain(t(11, 30), t(12, 0), t(12, 30), t(14, 30), t(15, 0));
        assertThat(times).contains(t(11, 0), t(16, 0));
    }

    @Test
    void 끝나는_시각에_바로_다음_예약은_가능하다() {
        List<Booking> bookings = List.of(booking(t(12, 0), t(13, 0), ReservationStatus.CONFIRMED));

        List<LocalTime> times = AvailableTimes.startTimes(HOURS, false, bookings, 60);

        assertThat(times).contains(t(11, 0), t(13, 0)); // 11:00~12:00, 13:00~14:00
    }

    @Test
    void 취소_노쇼_예약은_시간을_차지하지_않는다() {
        List<Booking> bookings =
                List.of(
                        booking(t(12, 0), t(13, 0), ReservationStatus.CANCELED),
                        booking(t(14, 0), t(15, 0), ReservationStatus.NO_SHOW));

        List<LocalTime> times = AvailableTimes.startTimes(HOURS, false, bookings, 60);

        assertThat(times).contains(t(12, 0), t(14, 0));
    }

    @Test
    void 정기_휴무_요일과_매장_스탭_휴무일은_선택지가_없다() {
        assertThat(AvailableTimes.startTimes(Optional.empty(), false, List.of(), 60)).isEmpty();
        assertThat(AvailableTimes.startTimes(HOURS, true, List.of(), 60)).isEmpty();
    }

    @Test
    void 영업_시작이_30분_단위가_아니면_다음_칸부터_연다() {
        Optional<OpeningHours> hours =
                Optional.of(new OpeningHours(LocalTime.of(10, 10), LocalTime.of(12, 0)));

        assertThat(AvailableTimes.startTimes(hours, false, List.of(), 60))
                .containsExactly(t(10, 30), t(11, 0));
    }

    @Test
    void 저장_재검사는_선택지와_같은_규칙이다() {
        List<Booking> bookings = List.of(booking(t(12, 0), t(13, 0), ReservationStatus.CONFIRMED));

        assertThat(AvailableTimes.isAvailable(HOURS, false, bookings, t(13, 0), 60)).isTrue();
        assertThat(AvailableTimes.isAvailable(HOURS, false, bookings, t(12, 30), 60)).isFalse();
        assertThat(AvailableTimes.isAvailable(HOURS, false, bookings, t(11, 15), 30)).isFalse();
        assertThat(AvailableTimes.isAvailable(HOURS, false, bookings, t(18, 30), 60)).isFalse();
        assertThat(AvailableTimes.isAvailable(HOURS, true, List.of(), t(13, 0), 60)).isFalse();
    }

    @Test
    void 소요_시간은_30분_단위여야_한다() {
        assertThatThrownBy(() -> AvailableTimes.startTimes(HOURS, false, List.of(), 45))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AvailableTimes.startTimes(HOURS, false, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

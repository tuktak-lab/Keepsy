package com.tuktaklab.keepsy.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-RSV-01·03, C8, E3, A13. 케이스는 Keepsy #55 · #59 · #60 · #61 「완료 조건」을 따른다. */
class ReservationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 9, 0);
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 24, 14, 0);

    private static Reservation withCustomer() {
        return Reservation.create(
                1L,
                10L,
                2L,
                START,
                90,
                ReservationChannel.PHONE,
                false,
                "젤 연장",
                List.of(100L),
                NOW);
    }

    private static Reservation withoutCustomer() {
        return Reservation.create(
                1L,
                null,
                2L,
                START,
                60,
                ReservationChannel.NAVER,
                true,
                null,
                List.of(100L, 200L),
                NOW);
    }

    @Test
    void 확정_상태로_만들고_종료_시각과_예정_시술을_채운다() {
        Reservation r = withCustomer();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(r.getEndAt()).isEqualTo(START.plusMinutes(90));
        assertThat(r.getCreatedAt()).isEqualTo(NOW);
        assertThat(r.getServices())
                .extracting(ReservationService::getServiceMenuId)
                .containsExactly(100L);
        assertThat(withoutCustomer().getServices()).hasSize(2);
    }

    @Test
    void 예약_경로를_비우면_매장이다() {
        Reservation r =
                Reservation.create(1L, 10L, 2L, START, 60, null, false, null, List.of(100L), NOW);

        assertThat(r.getChannel()).isEqualTo(ReservationChannel.STORE);
    }

    @Test
    void 예정_시술은_1개_이상이고_소요_시간은_30분_단위다() {
        assertThatThrownBy(() ->
                        Reservation.create(
                                1L, 10L, 2L, START, 60, null, false, null, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        Reservation.create(
                                1L, 10L, 2L, START, 45, null, false, null, List.of(100L), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 고객_없는_예약은_메모를_받지_않는다() {
        assertThatThrownBy(() ->
                        Reservation.create(
                                1L,
                                null,
                                2L,
                                START,
                                60,
                                ReservationChannel.NAVER,
                                false,
                                "메모",
                                List.of(100L),
                                NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 확정_예약만_취소할_수_있다() {
        Reservation r = withCustomer();
        r.cancel();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThatThrownBy(r::cancel).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 노쇼는_시작_시각이_지난_뒤에만_처리하고_고객_없는_예약도_된다() {
        Reservation r = withoutCustomer();

        assertThatThrownBy(() -> r.markNoShow(START)).isInstanceOf(IllegalStateException.class);
        r.markNoShow(START.plusMinutes(1));

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThatThrownBy(() -> r.markNoShow(START.plusHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 취소된_예약은_노쇼_처리할_수_없다() {
        Reservation r = withCustomer();
        r.cancel();

        assertThatThrownBy(() -> r.markNoShow(START.plusHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 고객_없는_확정_예약에만_고객을_연결한다() {
        Reservation r = withoutCustomer();
        r.linkCustomer(10L);

        assertThat(r.getCustomerId()).isEqualTo(10L);
        assertThatThrownBy(() -> r.linkCustomer(11L)).isInstanceOf(IllegalStateException.class);

        Reservation canceled = withoutCustomer();
        canceled.cancel();
        assertThatThrownBy(() -> canceled.linkCustomer(10L))
                .isInstanceOf(IllegalStateException.class);
    }
}

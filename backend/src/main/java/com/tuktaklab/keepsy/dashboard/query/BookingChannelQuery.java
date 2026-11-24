package com.tuktaklab.keepsy.dashboard.query;

import com.tuktaklab.keepsy.reservation.ReservationChannel;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 경로 통계 (FR-DSH-06, 결정 F27, CONTRACTS 6장 「성과 · 대시보드 · 검색」). 시작일이 기간 안인 예약을 상태와 관계없이 세고, 고객 없는
 * 예약도 넣는다 (E3). 화면용 조회라 조회 전용 SQL로 DB에서 센다 (DEVELOPMENT 4장 「도메인 연동」).
 */
@Repository
@RequiredArgsConstructor
public class BookingChannelQuery {

    public record Channel(String channel, long count, double ratio) {}

    public record Result(long total, List<Channel> channels) {}

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param to 포함
     * @return 예약 경로(3절 순서)마다 하나, 0건 경로 포함
     */
    @Transactional(readOnly = true)
    public Result find(long shopId, LocalDate from, LocalDate to) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(
                "SELECT channel, COUNT(*) AS n FROM reservation"
                        + " WHERE shop_id = :shopId AND start_at >= :from AND start_at < :to"
                        + " GROUP BY channel",
                Map.of(
                        "shopId",
                        shopId,
                        "from",
                        from.atStartOfDay(),
                        "to",
                        to.plusDays(1).atStartOfDay()),
                rs -> {
                    counts.put(rs.getString("channel"), rs.getLong("n"));
                });
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<Channel> channels =
                Arrays.stream(ReservationChannel.values())
                        .map(c -> {
                            long n = counts.getOrDefault(c.name(), 0L);
                            return new Channel(c.name(), n, total == 0 ? 0 : (double) n / total);
                        })
                        .toList();
        return new Result(total, channels);
    }
}

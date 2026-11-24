package com.tuktaklab.keepsy.search.query;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 상단 검색창의 고객 결과 (FR-SCH-04, CONTRACTS 6장 「성과 · 대시보드 · 검색」). 고객 도메인의 테이블을 읽는 화면용 횡단 조회라 조회 전용 SQL로
 * 필요한 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」). 삭제된 고객은 빼고 최근 등록순.
 */
@Repository
@RequiredArgsConstructor
public class CustomerSearchQuery {

    /** 번호는 마스킹한 값 {@code 010-****-1234} (NFR-04, A4) */
    public record Item(long customerId, String name, String phone) {}

    /**
     * {@code Customer.maskPhone}과 같은 규칙. 고객 엔티티를 import하지 않으려고 SQL에서 가린다. 전체 번호는 애플리케이션으로 읽어 오지 않는다
     */
    private static final String MASKED_PHONE =
            "CONCAT(LEFT(c.phone, 4), '****', SUBSTRING(c.phone, 9))";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param q 앞뒤 공백을 뺀 비어 있지 않은 검색어. 숫자 4자리면 휴대폰 뒷자리, 그 밖에는 이름 포함 ({@code GET /api/customers}와 같은
     *     규칙)
     */
    public List<Item> find(long shopId, String q, int page, int size) {
        MapSqlParameterSource p =
                params(shopId, q).addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(
                "SELECT c.id, c.name, "
                        + MASKED_PHONE
                        + " AS phone"
                        + from(q)
                        + " ORDER BY c.id DESC LIMIT :limit OFFSET :offset",
                p,
                (rs, i) -> new Item(rs.getLong("id"), rs.getString("name"), rs.getString("phone")));
    }

    public long count(long shopId, String q) {
        Long n = jdbc.queryForObject("SELECT COUNT(*)" + from(q), params(shopId, q), Long.class);
        return n == null ? 0 : n;
    }

    /** 조건 문장은 고정 문자열이고, 검색어는 바인딩한다 (NFR-08) */
    private static String from(String q) {
        return " FROM customer c WHERE c.shop_id = :shopId AND c.status = 'ACTIVE' AND "
                + (isLast4(q) ? "c.phone_last4 = :q" : "c.name LIKE :q");
    }

    private static MapSqlParameterSource params(long shopId, String q) {
        return new MapSqlParameterSource()
                .addValue("shopId", shopId)
                .addValue("q", isLast4(q) ? q : "%" + escapeLike(q) + "%");
    }

    private static boolean isLast4(String q) {
        return q.matches("\\d{4}");
    }

    /** LIKE 특수 문자를 글자 그대로 찾게 한다 (MySQL 기본 escape 문자 \) */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

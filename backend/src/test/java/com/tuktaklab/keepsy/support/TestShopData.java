package com.tuktaklab.keepsy.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 예약 통합 테스트용 모의 데이터 (실제 개인정보 아님). 매장 · 스탭 · 메뉴 · 고객 엔티티가 아직 없어 V1 · V2 테이블에 직접 넣고, {@link
 * #cleanUp}으로 만든 매장의 행을 지운다.
 */
public class TestShopData {

    private final JdbcTemplate jdbc;
    private final List<Long> shops = new ArrayList<>();
    private int phoneSeq;

    public TestShopData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 매일 10:00~19:00 영업하는 매장 */
    public long shop() {
        long id =
                insert(
                        "INSERT INTO shop (email, password_hash, name,"
                                + " mon_open_time, mon_close_time, tue_open_time, tue_close_time,"
                                + " wed_open_time, wed_close_time, thu_open_time, thu_close_time,"
                                + " fri_open_time, fri_close_time, sat_open_time, sat_close_time,"
                                + " sun_open_time, sun_close_time)"
                                + " VALUES (?, 'x', '모의 매장', '10:00', '19:00', '10:00', '19:00',"
                                + " '10:00', '19:00', '10:00', '19:00', '10:00', '19:00', '10:00',"
                                + " '19:00', '10:00', '19:00')",
                        "shop" + System.nanoTime() + "@example.com");
        shops.add(id);
        return id;
    }

    public long staff(long shopId, String status, boolean owner) {
        return insert(
                "INSERT INTO staff (shop_id, name, pin_hash, status, owner) VALUES (?, '스탭', 'x', ?, ?)",
                shopId,
                status,
                owner);
    }

    public long menu(long shopId, int minutes) {
        return insert(
                "INSERT INTO service_menu (shop_id, category, name, price, duration_minutes,"
                        + " cycle_min_days, cycle_max_days) VALUES (?, 'NAIL', '젤', 50000, ?, 21, 28)",
                shopId,
                minutes);
    }

    public long customer(long shopId, boolean restricted, String reason) {
        return insert(
                "INSERT INTO customer (shop_id, name, phone, registered_at, reservation_restricted,"
                        + " reservation_restrict_reason) VALUES (?, '모의 고객', ?, ?, ?, ?)",
                shopId,
                "010-0000-" + String.format("%04d", ++phoneSeq),
                LocalDateTime.of(2026, 1, 1, 0, 0),
                restricted,
                reason);
    }

    /**
     * 만든 매장의 행을 {@code shop_id} 칼럼이 있는 모든 테이블에서 지운다. 테이블 목록은 DB에서 읽으므로 migration이 테이블을 더해도 이 메서드를
     * 고치지 않는다 (CLAUDE.md 「충돌 줄이기」). 테이블 사이 FK 순서를 맞추지 않으려고 한 연결 안에서만 FK 검사를 잠깐 끈다.
     */
    public void cleanUp() {
        if (shops.isEmpty()) {
            return;
        }
        List<String> tables =
                jdbc.queryForList(
                        "SELECT table_name FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE() AND column_name = 'shop_id'",
                        String.class);
        jdbc.execute(
                (ConnectionCallback<Void>)
                        con -> {
                            try (Statement st = con.createStatement()) {
                                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                                try {
                                    for (long shopId : shops) {
                                        for (String table : tables) {
                                            delete(
                                                    con,
                                                    "DELETE FROM `" + table + "` WHERE shop_id = ?",
                                                    shopId);
                                        }
                                        delete(con, "DELETE FROM shop WHERE id = ?", shopId);
                                    }
                                } finally {
                                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                                }
                            }
                            return null;
                        });
        shops.clear();
    }

    private static void delete(Connection con, String sql, long shopId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, shopId);
            ps.executeUpdate();
        }
    }

    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    PreparedStatement ps =
                            con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    return ps;
                },
                key);
        return key.getKey().longValue();
    }
}

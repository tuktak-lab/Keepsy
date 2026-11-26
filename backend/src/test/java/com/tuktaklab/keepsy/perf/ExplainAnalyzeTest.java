package com.tuktaklab.keepsy.perf;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.perf.SqlCapture.Executed;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 주요 쿼리 EXPLAIN ANALYZE 측정 (Keepsy #350, ARCHITECTURE 8장, C17). {@code ./gradlew seedDemo
 * -Pseed.scale=perf}로 적재한 데모 매장에서 대상 API를 실제로 불러, 실행된 SQL · 실행 횟수 · 응답 시간(반복 측정)과 SQL마다 {@code
 * EXPLAIN ANALYZE}(읽은 행 · 실제 시간)를 {@code build/perf/explain-report.md}에 남긴다. 단언은 하지 않는다: 결과를 보고 사람이
 * 인덱스를 판단한다.
 *
 * <p>{@code ./gradlew perfExplain}으로만 돈다(태그 perf, 기본 test 제외). 데이터를 바꾸는 요청은 부르지 않고, 접근 로그
 * 파기(DELETE)는 {@code EXPLAIN}과 같은 조건의 {@code SELECT}로만 잰다. 접근 로그를 남기는 조회(고객 방문 이력)는 데모 DB에 접근 로그 행을
 * 더한다.
 */
@Tag("perf")
@SpringBootTest
@AutoConfigureMockMvc
@Import(PerfConfig.class)
class ExplainAnalyzeTest {

    /** 시드 생성기의 데모 매장 (SeedGenerator.EMAIL) */
    private static final String DEMO_EMAIL = "demo@example.com";

    private static final int WARMUP = 2;
    private static final int RUNS = 10;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired SqlCapture capture;
    @Autowired Clock clock;
    @Autowired List<ScheduledTaskHolder> schedulers;

    private long shop;
    private long owner;

    /** 측정 대상 하나: 이름 · 화면 · 요청 */
    private record Target(
            String name, String request, Supplier<MockHttpServletRequestBuilder> builder) {}

    @Test
    void 대상_API의_SQL과_실행_계획을_보고서로_남긴다() throws Exception {
        // 가상 발송 · 예약 발송 · 접근 로그 파기가 측정 중에 끼지 않게 멈춘다 (시드 생성기와 같은 방식)
        schedulers.forEach(h -> h.getScheduledTasks().forEach(ScheduledTask::cancel));
        shop = jdbc.queryForObject("SELECT id FROM shop WHERE email = ?", Long.class, DEMO_EMAIL);
        owner =
                jdbc.queryForObject(
                        "SELECT id FROM staff WHERE shop_id = ? AND owner ORDER BY id LIMIT 1",
                        Long.class,
                        shop);
        for (String table :
                List.of(
                        "customer",
                        "consent",
                        "reservation",
                        "reservation_service",
                        "visit",
                        "visit_service",
                        "payment",
                        "customer_prediction",
                        "prediction_snapshot",
                        "campaign",
                        "campaign_message",
                        "campaign_target",
                        "access_log")) {
            jdbc.execute("ANALYZE TABLE " + table); // 통계를 맞춘 뒤 잰다
        }

        StringBuilder report = new StringBuilder();
        header(report);
        for (Target t : targets()) {
            measure(report, t);
        }
        accessLogPurge(report);

        Path out = Path.of("build", "perf", "explain-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println("[perf] 보고서: " + out.toAbsolutePath());
    }

    private List<Target> targets() {
        LocalDate today = LocalDate.now(clock);
        Long campaign =
                jdbc
                        .query(
                                "SELECT id FROM campaign WHERE shop_id = ? AND message_kind = 'AD'"
                                        + " AND sent_at IS NOT NULL ORDER BY sent_at DESC LIMIT 1",
                                (rs, i) -> rs.getLong(1),
                                shop)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("발송된 광고 캠페인이 없습니다"));
        Long busyCustomer =
                jdbc.queryForObject(
                        "SELECT customer_id FROM visit WHERE shop_id = ? AND customer_id IS NOT NULL"
                                + " GROUP BY customer_id ORDER BY COUNT(*) DESC, customer_id LIMIT 1",
                        Long.class,
                        shop);
        String last4 =
                jdbc.queryForObject(
                        "SELECT phone_last4 FROM customer WHERE shop_id = ? AND status = 'ACTIVE'"
                                + " ORDER BY id LIMIT 1",
                        String.class,
                        shop);
        String namePart =
                jdbc.queryForObject(
                        "SELECT LEFT(name, 1) FROM customer WHERE shop_id = ? AND status = 'ACTIVE'"
                                + " ORDER BY id LIMIT 1",
                        String.class,
                        shop);
        LocalDate from = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate yearAgo = today.minusYears(1);
        return List.of(
                new Target(
                        "1. 예약 캘린더 (이번 주)",
                        "GET /api/reservations?from=%s&to=%s".formatted(from, from.plusDays(6)),
                        () ->
                                get("/api/reservations")
                                        .param("from", from.toString())
                                        .param("to", from.plusDays(6).toString())),
                new Target(
                        "2. 대시보드 오늘 (마감 안 된 예약 포함)",
                        "GET /api/dashboard/today",
                        () -> get("/api/dashboard/today")),
                new Target(
                        "3a. 고객 검색 (이름 일부)",
                        "GET /api/search/customers?q=" + namePart,
                        () -> get("/api/search/customers").param("q", namePart)),
                new Target(
                        "3a. 고객 검색 (번호 끝 4자리)",
                        "GET /api/search/customers?q=" + last4,
                        () -> get("/api/search/customers").param("q", last4)),
                new Target("3b. 고객 목록 (조건 없음)", "GET /api/customers", () -> get("/api/customers")),
                new Target(
                        "3b. 고객 목록 (네일 · 주기 도래)",
                        "GET /api/customers?category=NAIL&predictionStatus=DUE",
                        () ->
                                get("/api/customers")
                                        .param("category", "NAIL")
                                        .param("predictionStatus", "DUE")),
                new Target(
                        "3c. 고객 방문 이력 (방문이 가장 많은 고객)",
                        "GET /api/customers/%d/history".formatted(busyCustomer),
                        () -> get("/api/customers/{id}/history", busyCustomer)),
                new Target(
                        "4. 재방문 관리 (주기 도래)",
                        "GET /api/insights?status=DUE",
                        () -> get("/api/insights").param("status", "DUE")),
                new Target(
                        "4. 오늘 관리할 고객",
                        "GET /api/dashboard/care-targets",
                        () -> get("/api/dashboard/care-targets")),
                new Target(
                        "5. 캠페인 대상 (네일 · 주기 도래)",
                        "POST /api/campaigns/targets {category: NAIL, predictionStatus: DUE}",
                        () ->
                                post("/api/campaigns/targets")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"category\":\"NAIL\",\"predictionStatus\":\"DUE\"}")),
                new Target(
                        "6a. 캠페인 퍼널 (최근 광고)",
                        "GET /api/campaigns/%d/funnel".formatted(campaign),
                        () -> get("/api/campaigns/{id}/funnel", campaign)),
                new Target(
                        "6b. 캠페인 연결 매출",
                        "GET /api/campaigns/%d/revenue".formatted(campaign),
                        () -> get("/api/campaigns/{id}/revenue", campaign)),
                new Target(
                        "6c. 대조군 비교",
                        "GET /api/campaigns/%d/control-comparison".formatted(campaign),
                        () -> get("/api/campaigns/{id}/control-comparison", campaign)),
                new Target(
                        "6d. 캠페인 성과 목록 (최근 1년)",
                        "GET /api/analytics/campaigns?period=CUSTOM&from=%s&to=%s"
                                .formatted(yearAgo, today),
                        () ->
                                get("/api/analytics/campaigns")
                                        .param("period", "CUSTOM")
                                        .param("from", yearAgo.toString())
                                        .param("to", today.toString())),
                new Target(
                        "7. 매장 요약 (최근 1년)",
                        "GET /api/analytics/summary?period=CUSTOM&from=%s&to=%s"
                                .formatted(yearAgo, today),
                        () ->
                                get("/api/analytics/summary")
                                        .param("period", "CUSTOM")
                                        .param("from", yearAgo.toString())
                                        .param("to", today.toString())));
    }

    private void header(StringBuilder r) {
        r.append("# #350 EXPLAIN ANALYZE 측정\n\n");
        r.append("- 측정 시각: ").append(LocalDateTime.now(clock)).append(" (Asia/Seoul)\n");
        r.append("- MySQL: ")
                .append(jdbc.queryForObject("SELECT VERSION()", String.class))
                .append('\n');
        r.append("- 반복: 예열 ")
                .append(WARMUP)
                .append("회 뒤 ")
                .append(RUNS)
                .append("회 (응답 시간은 MockMvc 안에서 잰 값)\n");
        r.append("- 시드: ")
                .append(System.getProperty("seed.scale", "?"))
                .append(", random=")
                .append(System.getProperty("seed.random", "?"))
                .append(", until=")
                .append(System.getProperty("seed.until", "?"))
                .append("\n\n");
        r.append("| 표 | 데모 매장 행 수 |\n| --- | ---: |\n");
        Map<String, String> counts = new LinkedHashMap<>();
        counts.put("customer", "SELECT COUNT(*) FROM customer WHERE shop_id = ?");
        counts.put("reservation", "SELECT COUNT(*) FROM reservation WHERE shop_id = ?");
        counts.put("visit", "SELECT COUNT(*) FROM visit WHERE shop_id = ?");
        counts.put("visit_service", "SELECT COUNT(*) FROM visit_service WHERE shop_id = ?");
        counts.put("payment", "SELECT COUNT(*) FROM payment WHERE shop_id = ?");
        counts.put("consent", "SELECT COUNT(*) FROM consent WHERE shop_id = ?");
        counts.put(
                "customer_prediction",
                "SELECT COUNT(*) FROM customer_prediction WHERE shop_id = ?");
        counts.put(
                "prediction_snapshot",
                "SELECT COUNT(*) FROM prediction_snapshot WHERE shop_id = ?");
        counts.put("campaign", "SELECT COUNT(*) FROM campaign WHERE shop_id = ?");
        counts.put("campaign_message", "SELECT COUNT(*) FROM campaign_message WHERE shop_id = ?");
        counts.put("access_log", "SELECT COUNT(*) FROM access_log WHERE shop_id = ?");
        counts.forEach((table, sql) ->
                r.append("| ")
                        .append(table)
                        .append(" | ")
                        .append(jdbc.queryForObject(sql, Long.class, shop))
                        .append(" |\n"));
        r.append('\n');
    }

    private void measure(StringBuilder r, Target t) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            mvc.perform(t.builder().get().session(session()));
        }
        List<Long> micros = new ArrayList<>();
        List<Executed> sqls = List.of();
        int status = 0;
        for (int i = 0; i < RUNS; i++) {
            if (i == 0) {
                capture.start();
            }
            long started = System.nanoTime();
            status =
                    mvc.perform(t.builder().get().session(session()))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            micros.add((System.nanoTime() - started) / 1_000);
            if (i == 0) {
                sqls = capture.stop();
            }
        }
        micros.sort(null);
        r.append("## ").append(t.name()).append("\n\n");
        r.append("- 요청: `").append(t.request()).append("` → HTTP ").append(status).append('\n');
        r.append("- 응답 시간(ms): 중앙값 ")
                .append(ms(micros.get(micros.size() / 2)))
                .append(" · 최소 ")
                .append(ms(micros.get(0)))
                .append(" · 최대 ")
                .append(ms(micros.get(micros.size() - 1)))
                .append('\n');
        r.append("- 요청 1회에 실행된 SQL: ").append(sqls.size()).append("개\n\n");
        int n = 0;
        for (Executed e : sqls) {
            n++;
            r.append("### SQL ")
                    .append(n)
                    .append("\n\n```sql\n")
                    .append(e.sql().strip())
                    .append("\n```\n\n");
            if (!e.params().isEmpty()) {
                r.append("바인딩: `").append(e.params().values()).append("`\n\n");
            }
            if (isSelect(e.sql())) {
                r.append("```text\n")
                        .append(explain("EXPLAIN ANALYZE " + e.sql(), e.params()))
                        .append("\n```\n\n");
            } else {
                r.append("(SELECT가 아니라 실행 계획을 재지 않음)\n\n");
            }
        }
    }

    /** 접근 로그 파기(D2): DELETE는 실행하지 않고 EXPLAIN과, 같은 조건의 SELECT EXPLAIN ANALYZE로 잰다 */
    private void accessLogPurge(StringBuilder r) {
        LocalDateTime before = LocalDateTime.now(clock).minusYears(2);
        Map<Integer, Object> p = Map.of(1, before);
        r.append("## 8. 접근 로그 파기 (스케줄러, 매일 03:30)\n\n");
        r.append("```sql\nDELETE FROM access_log WHERE accessed_at < ?\n```\n\n바인딩: `[")
                .append(before)
                .append("]`\n\n");
        r.append("```text\n")
                .append(explain("EXPLAIN DELETE FROM access_log WHERE accessed_at < ?", p))
                .append("\n```\n\n");
        r.append("같은 조건의 SELECT:\n\n```text\n")
                .append(
                        explain(
                                "EXPLAIN ANALYZE SELECT id FROM access_log WHERE accessed_at < ?",
                                p))
                .append("\n```\n\n");
    }

    private String explain(String sql, Map<Integer, Object> params) {
        try {
            List<String> lines = new ArrayList<>();
            jdbc.query(
                    sql,
                    rs -> {
                        int columns = rs.getMetaData().getColumnCount();
                        StringBuilder row = new StringBuilder();
                        for (int c = 1; c <= columns; c++) {
                            row.append(c > 1 ? " | " : "").append(rs.getString(c));
                        }
                        lines.add(row.toString());
                    },
                    params.values().toArray());
            return String.join("\n", lines);
        } catch (RuntimeException e) {
            return "실행 계획을 읽지 못함: " + e.getClass().getSimpleName();
        }
    }

    private static boolean isSelect(String sql) {
        String s = sql.strip().toLowerCase(Locale.ROOT);
        return s.startsWith("select") || s.startsWith("with") || s.startsWith("(select");
    }

    private static String ms(long micros) {
        return String.format(Locale.ROOT, "%.1f", micros / 1000.0);
    }

    /** 데모 매장 원장 · PIN 인증 세션. PIN 만료는 앱 시계 기준 */
    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, owner);
        shopSession.setPin(session, owner, LocalDateTime.now(clock).plusMinutes(5));
        return session;
    }
}

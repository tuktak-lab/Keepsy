package com.tuktaklab.keepsy.seed;

import com.tuktaklab.keepsy.account.Shop;
import com.tuktaklab.keepsy.account.ShopInfoService;
import com.tuktaklab.keepsy.account.ShopInfoService.ShopInfoChange;
import com.tuktaklab.keepsy.account.ShopSettingsService;
import com.tuktaklab.keepsy.account.ShopSignupService;
import com.tuktaklab.keepsy.account.StaffCommandService;
import com.tuktaklab.keepsy.account.StaffCommandService.NewStaff;
import com.tuktaklab.keepsy.campaign.CampaignSendService;
import com.tuktaklab.keepsy.campaign.CampaignService;
import com.tuktaklab.keepsy.campaign.VirtualDelivery;
import com.tuktaklab.keepsy.campaign.query.CampaignTargetQuery.Selection;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.customer.Consent;
import com.tuktaklab.keepsy.customer.Customer;
import com.tuktaklab.keepsy.customer.CustomerService;
import com.tuktaklab.keepsy.customer.NewCustomer;
import com.tuktaklab.keepsy.prediction.PredictionStatus;
import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.reservation.ReservationChannel;
import com.tuktaklab.keepsy.seed.SeedConfig.SeedClock;
import com.tuktaklab.keepsy.seed.SeedConfig.SeedRandom;
import com.tuktaklab.keepsy.settings.ServiceMenuService;
import com.tuktaklab.keepsy.settings.ServiceMenuService.NewMenu;
import com.tuktaklab.keepsy.visit.Closing;
import com.tuktaklab.keepsy.visit.PaymentMethod;
import com.tuktaklab.keepsy.visit.VisitAppService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * C17 시드 생성기 (Keepsy #335, docs 결정체크리스트 C17, DEVELOPMENT 7장 「시드 데이터」). 매장 1곳 · 스탭 5명(네일 2 · 속눈썹 1 ·
 * 왁싱 1 · 반영구 1)인 뷰티샵의 날짜를 하루씩 앞으로 돌리며 예약 · 취소 · 노쇼 · 마감 · 캠페인을 실제 서비스로 기록한다.
 *
 * <p>시계를 이벤트 시각으로 옮겨 가며 기록하므로 예측 재계산 · 스냅샷 · 귀속이 그 시각까지의 방문만 본다. 예측 재계산은 고객의 방문을 날짜 조건 없이 읽기
 * 때문에(JdbcPredictionVisits) 방문을 한꺼번에 넣으면 과거 예측에 미래 방문이 섞인다.
 *
 * <p>모의 데이터만 만든다: 합성 이름, 가짜 번호 {@code 010-0000-NNNN}. 같은 {@code seed.random} · {@code seed.until}이면
 * 같은 데이터가 나온다. 하루 건수는 C17 표(네일 1인 4건, 속눈썹 · 왁싱 5건, 반영구 3건)이고 월요일은 휴무다.
 */
@RequiredArgsConstructor
class SeedGenerator {

    /** 데모 매장 로그인 (로컬 시연용 모의 매장, 실제 계정 아님). 원장 PIN 1111, 스탭 2222~5555 */
    static final String EMAIL = "demo@example.com";

    static final String PASSWORD = "keepsy-demo-1234";
    static final String SHOP_NAME = "킵시 데모 뷰티샵";

    private static final int LOOKAHEAD_DAYS = 7;
    private static final double CROSS_SELL = 0.12;
    private static final double WALK_IN = 0.12;
    private static final double REBOOK = 0.3;
    private static final int CAMPAIGN_EVERY_DAYS = 14;
    private static final int CONTROL_PERCENT = 20;

    enum Scale {
        /** 60일, 스탭마다 하루 1건. 같은 seed 재현 검사용 */
        SMALL(60, 1, 120),
        /** 1년, C17 하루 건수 */
        DEMO(365, 0, 1000),
        /** 3년, C17 (매장 1 · 스탭 5 · 고객 약 3천 · 방문 약 1.9만) */
        PERF(365 * 3, 0, 3000);

        final int days;
        final int slotsPerStaff;

        /** 목표 고객 수. 등록은 기간에 고르게 나눈 선을 넘지 않는다 */
        final int customers;

        Scale(int days, int slotsPerStaff, int customers) {
            this.customers = customers;
            this.days = days;
            this.slotsPerStaff = slotsPerStaff;
        }
    }

    /**
     * @param random 고정 난수 seed
     * @param until 기준일(시연일). 그 전날까지 마감하고, 그날부터는 확정 예약만 남긴다
     * @param recreate 데모 매장이 이미 있으면 그 매장 행만 지우고 다시 만든다
     */
    record Options(Scale scale, long random, LocalDate until, boolean recreate) {}

    record Report(long shopId, Map<String, Object> aggregates, Duration elapsed) {}

    record Menu(String name, int price, int minutes, String attribute, int weight) {}

    enum Cat {
        NAIL(
                4,
                120,
                21,
                28,
                0.15,
                List.of(
                        new Menu("젤 원컬러", 50_000, 90, "NORMAL", 60),
                        new Menu("젤 아트", 70_000, 120, "NORMAL", 37),
                        new Menu("젤 제거", 15_000, 30, "END_SIGNAL", 3))),
        LASH(
                5,
                90,
                30,
                60,
                0.18,
                List.of(
                        new Menu("속눈썹 연장", 60_000, 90, "NORMAL", 80),
                        new Menu("속눈썹 펌", 40_000, 60, "NORMAL", 20))),
        WAXING(
                5,
                90,
                28,
                42,
                0.17,
                List.of(
                        new Menu("브라질리언 왁싱", 70_000, 60, "NORMAL", 60),
                        new Menu("겨드랑이 왁싱", 20_000, 30, "NORMAL", 40))),
        /** 첫 시술 뒤 리터치(주기 계산 제외, E5), 그 뒤 1~2년 */
        SEMI_PERMANENT(
                3,
                150,
                365,
                730,
                0,
                List.of(
                        new Menu("눈썹 반영구", 250_000, 150, "NORMAL", 100),
                        new Menu("반영구 리터치", 80_000, 90, "EXCLUDE", 0)));

        final int daily;
        final int slotMinutes;
        final int cycleMin;
        final int cycleMax;
        final double churn;
        final List<Menu> menus;

        Cat(
                int daily,
                int slotMinutes,
                int cycleMin,
                int cycleMax,
                double churn,
                List<Menu> menus) {
            this.daily = daily;
            this.slotMinutes = slotMinutes;
            this.cycleMin = cycleMin;
            this.cycleMax = cycleMax;
            this.churn = churn;
            this.menus = menus;
        }
    }

    private enum Outcome {
        VISIT,
        NO_SHOW,
        CANCEL
    }

    private static final String[] SURNAMES = {
        "김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권", "황", "안", "송",
        "류", "홍"
    };
    private static final String[] GIVEN_1 = {
        "서", "지", "민", "수", "하", "예", "유", "채", "다", "소", "윤", "은", "현", "가", "나", "아", "세", "주",
        "시", "연"
    };
    private static final String[] GIVEN_2 = {
        "연", "은", "윤", "아", "린", "진", "희", "빈", "원", "현", "서", "영", "우", "율", "경", "솔"
    };
    private static final String[] MEMOS = {
        "다음엔 누드톤 원함",
        "왼손 약지 손톱 갈라짐 주의",
        "컬 C → D로 바꿔 봄",
        "피부 붉어짐 없었음",
        "시간 넉넉히 잡아 달라고 함",
        "디자인 사진 저장해 둠"
    };

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationContext context;
    private final List<ScheduledTaskHolder> schedulers;
    private final SeedClock clock;
    private final SeedRandom serviceRandom;
    private final ShopSignupService signup;
    private final ShopInfoService shopInfo;
    private final ShopSettingsService shopSettings;
    private final StaffCommandService staffCommands;
    private final ServiceMenuService menus;
    private final CustomerService customers;
    private final ReservationAppService reservations;
    private final VisitAppService visits;
    private final CampaignService campaigns;
    private final CampaignSendService sends;

    Report run(Options options) {
        // 시드 중 스케줄러(가상 발송 · 예약 발송 · 접근 로그 파기)가 시드 시계로 돌지 않게 멈춘다. 발송 결과는 생성기가 직접 정한다
        schedulers.forEach(h -> h.getScheduledTasks().forEach(ScheduledTask::cancel));
        Object campaignTarget = AopTestUtils.getUltimateTargetObject(campaigns);
        if (ReflectionTestUtils.getField(campaignTarget, "campaignControlRandom")
                != serviceRandom) {
            throw new IllegalStateException("대조군 배정 난수가 시드 난수로 바뀌지 않았습니다");
        }
        serviceRandom.reset(options.random());
        long started = System.nanoTime();
        long shopId = new Simulation(options).run();
        return new Report(
                shopId, aggregates(shopId), Duration.ofNanos(System.nanoTime() - started));
    }

    /** 재현 검사 · 적재 보고용 핵심 집계. ID는 실행마다 달라 값만 모은다 */
    Map<String, Object> aggregates(long shopId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("staff", count("SELECT COUNT(*) FROM staff WHERE shop_id = ?", shopId));
        a.put("customers", count("SELECT COUNT(*) FROM customer WHERE shop_id = ?", shopId));
        a.put("visits", count("SELECT COUNT(*) FROM visit WHERE shop_id = ?", shopId));
        a.put(
                "visitsWithoutCustomer",
                count(
                        "SELECT COUNT(*) FROM visit WHERE shop_id = ? AND customer_id IS NULL",
                        shopId));
        a.put(
                "visitServicesByCategory",
                grouped(
                        "SELECT category, COUNT(*) FROM visit_service WHERE shop_id = ?"
                                + " GROUP BY category ORDER BY category",
                        shopId));
        a.put(
                "cycleExcluded",
                count(
                        "SELECT COUNT(*) FROM visit_service WHERE shop_id = ? AND cycle_excluded",
                        shopId));
        a.put(
                "attributedServices",
                count(
                        "SELECT COUNT(*) FROM visit_service WHERE shop_id = ?"
                                + " AND attributed_message_id IS NOT NULL",
                        shopId));
        a.put(
                "reservationsByStatus",
                grouped(
                        "SELECT status, COUNT(*) FROM reservation WHERE shop_id = ?"
                                + " GROUP BY status ORDER BY status",
                        shopId));
        a.put(
                "reservationsByChannel",
                grouped(
                        "SELECT channel, COUNT(*) FROM reservation WHERE shop_id = ?"
                                + " GROUP BY channel ORDER BY channel",
                        shopId));
        a.put(
                "paymentTotal",
                count(
                        "SELECT COALESCE(SUM(amount), 0) FROM payment WHERE shop_id = ? AND NOT canceled",
                        shopId));
        a.put("campaigns", count("SELECT COUNT(*) FROM campaign WHERE shop_id = ?", shopId));
        a.put(
                "controlTargets",
                count(
                        "SELECT COUNT(*) FROM campaign_target WHERE shop_id = ? AND control_group",
                        shopId));
        a.put(
                "messagesByStatus",
                grouped(
                        "SELECT COALESCE(send_status, 'CONTROL'), COUNT(*) FROM campaign_message"
                                + " WHERE shop_id = ? GROUP BY 1 ORDER BY 1",
                        shopId));
        a.put(
                "predictions",
                count("SELECT COUNT(*) FROM customer_prediction WHERE shop_id = ?", shopId));
        a.put(
                "snapshots",
                count("SELECT COUNT(*) FROM prediction_snapshot WHERE shop_id = ?", shopId));
        a.put(
                "snapshotHits",
                count(
                        "SELECT COUNT(*) FROM prediction_snapshot WHERE shop_id = ? AND hit",
                        shopId));
        return a;
    }

    /** 데모 매장의 행만 지운다. 테이블 목록은 DB에서 읽는다 (TestShopData.cleanUp과 같은 방식). 다른 매장 · 전체 초기화는 하지 않는다 */
    void delete(long shopId) {
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
                                    for (String table : tables) {
                                        try (PreparedStatement ps =
                                                con.prepareStatement(
                                                        "DELETE FROM `"
                                                                + table
                                                                + "` WHERE shop_id = ?")) {
                                            ps.setLong(1, shopId);
                                            ps.executeUpdate();
                                        }
                                    }
                                    try (PreparedStatement ps =
                                            con.prepareStatement("DELETE FROM shop WHERE id = ?")) {
                                        ps.setLong(1, shopId);
                                        ps.executeUpdate();
                                    }
                                } finally {
                                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                                }
                            }
                            return null;
                        });
    }

    private long count(String sql, long shopId) {
        return jdbc.queryForObject(sql, Long.class, shopId);
    }

    private String grouped(String sql, long shopId) {
        return jdbc.query(
                sql,
                rs -> {
                    StringJoiner j = new StringJoiner(", ");
                    while (rs.next()) {
                        j.add(rs.getString(1) + "=" + rs.getLong(2));
                    }
                    return j.toString();
                },
                shopId);
    }

    private static final class Person {
        final long customerId;
        final Map<Cat, Member> members = new EnumMap<>(Cat.class);

        Person(long customerId) {
            this.customerId = customerId;
        }
    }

    /** 고객 한 명의 카테고리 하나. 활성이면 {@code due}에 다시 올 날을 기다리고, 이탈하면 캠페인에만 반응한다 */
    private static final class Member {
        final int seq;
        final Person person;
        final Cat cat;
        final double pace;
        LocalDate due;
        boolean active = true;
        boolean retouchNext;

        Member(int seq, Person person, Cat cat, double pace, LocalDate due) {
            this.seq = seq;
            this.person = person;
            this.cat = cat;
            this.pace = pace;
            this.due = due;
        }
    }

    private static final class Staff {
        final long id;
        final Cat cat;
        final int index;
        final int slots;
        final Map<LocalDate, Booking[]> days = new HashMap<>();

        Staff(long id, Cat cat, int index, int slots) {
            this.id = id;
            this.cat = cat;
            this.index = index;
            this.slots = slots;
        }

        Booking[] slots(LocalDate date) {
            return days.computeIfAbsent(date, d -> new Booking[slots]);
        }
    }

    private static final class Booking {
        final Staff staff;
        final int slot;
        final LocalDate date;
        final LocalDateTime start;
        final LocalDateTime end;
        final Member member;
        final List<Menu> menus;
        final Outcome outcome;
        final long id;

        Booking(
                Staff staff,
                int slot,
                LocalDate date,
                LocalDateTime start,
                Member member,
                List<Menu> menus,
                Outcome outcome,
                long id) {
            this.staff = staff;
            this.slot = slot;
            this.date = date;
            this.start = start;
            this.end = start.plusMinutes(menus.stream().mapToInt(Menu::minutes).sum());
            this.member = member;
            this.menus = menus;
            this.outcome = outcome;
            this.id = id;
        }

        boolean overlaps(LocalDateTime from, LocalDateTime to) {
            return start.isBefore(to) && from.isBefore(start.plusMinutes(staff.cat.slotMinutes));
        }
    }

    /** 한 번의 생성. 상태는 실행마다 새로 만든다 */
    private final class Simulation {
        private final Options o;
        private final Random rnd;
        private final LocalDate start;
        private final List<Staff> staff = new ArrayList<>();
        private final Map<Cat, TreeSet<Member>> due = new EnumMap<>(Cat.class);
        private final Map<Cat, LinkedHashSet<Member>> dormant = new EnumMap<>(Cat.class);
        private final List<Person> people = new ArrayList<>();
        private final Map<Long, Person> peopleById = new HashMap<>();
        private final Map<LocalDate, List<Booking>> byDate = new HashMap<>();
        private final Map<Menu, Long> menuIds = new HashMap<>();
        private LocalDateTime now;
        private long shopId;
        private long ownerId;
        private long revisitTemplateId;
        private long winBackTemplateId;
        private int memberSeq;
        private int campaignCount;

        Simulation(Options o) {
            this.o = o;
            this.rnd = new Random(o.random());
            this.start = o.until().minusDays(o.scale().days);
            for (Cat c : Cat.values()) {
                dormant.put(c, new LinkedHashSet<>());
                due.put(
                        c,
                        new TreeSet<>(
                                Comparator.comparing((Member m) -> m.due)
                                        .thenComparingInt(m -> m.seq)));
            }
        }

        long run() {
            now = start.minusDays(1).atTime(9, 0);
            clock.at(now);
            setUp();
            for (LocalDate d = start; ; d = d.plusDays(1)) {
                morning(d);
                if (!d.isBefore(o.until())) {
                    return shopId;
                }
                day(d);
            }
        }

        private void setUp() {
            Long existing =
                    jdbc.query(
                            "SELECT id FROM shop WHERE email = ?",
                            rs -> rs.next() ? rs.getLong(1) : null,
                            EMAIL);
            if (existing != null) {
                if (!o.recreate()) {
                    throw new IllegalStateException(
                            "데모 매장(" + EMAIL + ")이 이미 있습니다. 다시 만들려면 -Pseed.recreate=true");
                }
                delete(existing);
            }
            shopId = signup.signUp(EMAIL, PASSWORD, SHOP_NAME, "원장", "1111");
            ownerId =
                    jdbc.queryForObject(
                            "SELECT id FROM staff WHERE shop_id = ? AND owner", Long.class, shopId);
            shopInfo.update(
                    shopId,
                    new ShopInfoChange(
                            SHOP_NAME, "02-000-0000", "02-000-0000", "080-000-0000", hours()));
            // 귀속 14일 · 재발송 제한 7일 · 보관 3년은 기본값. 가상 발송 실패 0%로 결과를 난수와 떼어 놓는다
            shopSettings.updateSettings(shopId, new Shop.Settings(120, 14, 1000, 7, 0, 3));

            int slotsOverride = o.scale().slotsPerStaff;
            String[][] members = {
                {"네일 디자이너", "2222"}, {"속눈썹 디자이너", "3333"}, {"왁싱 디자이너", "4444"}, {"반영구 디자이너", "5555"}
            };
            Cat[] cats = {Cat.NAIL, Cat.LASH, Cat.WAXING, Cat.SEMI_PERMANENT};
            staff.add(new Staff(ownerId, Cat.NAIL, 0, slots(Cat.NAIL, slotsOverride)));
            for (int i = 0; i < members.length; i++) {
                long id =
                        staffCommands.create(
                                shopId,
                                new NewStaff(members[i][0], members[i][1], false, List.of()));
                staff.add(new Staff(id, cats[i], i + 1, slots(cats[i], slotsOverride)));
            }
            for (Cat c : Cat.values()) {
                for (Menu m : c.menus) {
                    menuIds.put(
                            m,
                            menus.create(
                                    shopId,
                                    new NewMenu(
                                            c.name(),
                                            m.name(),
                                            m.price(),
                                            m.minutes(),
                                            c.cycleMin,
                                            c.cycleMax,
                                            m.attribute())));
                }
            }
            revisitTemplateId = template("REVISIT");
            winBackTemplateId = template("WIN_BACK");
        }

        /** 가입 때 만든 기본 템플릿 (FR-CMP-03) */
        private long template(String purpose) {
            return jdbc.queryForObject(
                    "SELECT id FROM message_template WHERE shop_id = ? AND purpose = ?"
                            + " ORDER BY id LIMIT 1",
                    Long.class,
                    shopId,
                    purpose);
        }

        private static int slots(Cat c, int override) {
            return override > 0 ? override : c.daily;
        }

        private List<Shop.OpeningHours> hours() {
            List<Shop.OpeningHours> hours = new ArrayList<>();
            for (DayOfWeek day : DayOfWeek.values()) {
                hours.add(
                        day == DayOfWeek.MONDAY
                                ? new Shop.OpeningHours(day, null, null)
                                : new Shop.OpeningHours(day, openTime(day), LocalTime.of(20, 30)));
            }
            return hours;
        }

        private static LocalTime openTime(DayOfWeek day) {
            return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                    ? LocalTime.of(11, 0)
                    : LocalTime.of(10, 0);
        }

        private static boolean open(LocalDate date) {
            return date.getDayOfWeek() != DayOfWeek.MONDAY;
        }

        private static LocalDateTime slotStart(LocalDate date, Cat c, int slot) {
            return date.atTime(openTime(date.getDayOfWeek()))
                    .plusMinutes((long) slot * c.slotMinutes);
        }

        /** 시계는 앞으로만 간다. 같은 시각 이벤트는 1초씩 띄운다 */
        private void at(LocalDateTime t) {
            now = t.isAfter(now) ? t : now.plusSeconds(1);
            clock.at(now);
        }

        private List<Booking> bookings(LocalDate d) {
            return byDate.getOrDefault(d, List.of());
        }

        /** 08:00 미리 잡힌 취소, 09:00 앞으로 7일 예약 받기 */
        private void morning(LocalDate d) {
            at(d.atTime(8, 0));
            for (Booking b : List.copyOf(bookings(d))) {
                if (b.outcome == Outcome.CANCEL) {
                    at(now);
                    reservations.cancel(shopId, b.id);
                    byDate.get(d).remove(b);
                    b.staff.slots(d)[b.slot] = null;
                    due.get(b.member.cat).add(b.member);
                }
            }
            at(d.atTime(9, 0));
            for (int lead = 0; lead <= LOOKAHEAD_DAYS; lead++) {
                LocalDate t = d.plusDays(lead);
                if (!open(t)) {
                    continue;
                }
                // 당일 빈자리는 모두 채우고(전화 · 방문 손님), 며칠 앞은 일부만 미리 찬다
                double p = lead == 0 ? 1 : lead <= 2 ? 0.5 : 0.25;
                for (Staff s : staff) {
                    Booking[] slots = s.slots(t);
                    for (int i = 0; i < slots.length; i++) {
                        if (slots[i] == null && rnd.nextDouble() < p) {
                            book(s, t, i, lead);
                        }
                    }
                }
            }
        }

        private void book(Staff s, LocalDate t, int slot, int lead) {
            LocalDateTime from = slotStart(t, s.cat, slot);
            LocalDateTime to = from.plusMinutes(s.cat.slotMinutes);
            // 등록 고객이 목표선 아래면 가까운 빈자리를 새 손님에게 먼저 준다. 선 이상이면 기다리던 고객부터
            Member m = lead <= 2 && !reachedTarget() ? null : pickDue(s.cat, t, from, to);
            if (m == null) {
                if (lead > 2) {
                    return;
                }
                if (lead == 0 && rnd.nextDouble() < WALK_IN) {
                    // 동의를 받지 못한 손님: 고객 없는 예약 · 방문 (E3)
                    create(s, t, slot, null, List.of(weighted(s.cat)), 0, ReservationChannel.STORE);
                    return;
                }
                m = newcomer(s.cat, t, from, to);
            }
            create(s, t, slot, m, menusFor(m), lead, channel(lead));
        }

        /** 등록 고객이 오늘까지의 목표선(규모별 고객 수를 기간에 고르게 나눈 값)에 닿았는지 */
        private boolean reachedTarget() {
            return people.size()
                    >= (long) o.scale().customers
                            * (ChronoUnit.DAYS.between(start, now.toLocalDate()) + 1)
                            / o.scale().days;
        }

        /** 다시 올 날이 된 고객 중 가장 오래 기다린 사람. 그 시간에 다른 예약이 있으면 건너뛴다 */
        private Member pickDue(Cat c, LocalDate t, LocalDateTime from, LocalDateTime to) {
            TreeSet<Member> pool = due.get(c);
            for (Member m : pool) {
                if (m.due.isAfter(t)) {
                    return null;
                }
                if (free(m.person, t, from, to)) {
                    pool.remove(m);
                    return m;
                }
            }
            return null;
        }

        private boolean free(Person p, LocalDate t, LocalDateTime from, LocalDateTime to) {
            for (Booking b : bookings(t)) {
                if (b.member != null && b.member.person == p && b.overlaps(from, to)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 빈자리 손님. 일부는 다른 카테고리를 받던 기존 고객이다 (같은 날 여러 시술, C6). 등록 고객이 목표선(규모별 고객 수를 기간에 고르게 나눈 값)에
         * 닿았으면 새로 등록하지 않고 이탈했던 고객이 돌아오거나 기존 고객이 받는다. 선 아래면 {@link #book}이 가까운 빈자리를 새 손님에게 먼저 준다.
         * 그래서 고객 수는 이탈률과 상관없이 C17 목표선을 따라간다.
         */
        private Member newcomer(Cat c, LocalDate t, LocalDateTime from, LocalDateTime to) {
            boolean full = reachedTarget();
            if (full) {
                for (Member m : dormant.get(c)) {
                    if (free(m.person, t, from, to)) {
                        dormant.get(c).remove(m);
                        m.active = true;
                        m.due = t;
                        return m;
                    }
                }
            }
            Person p = null;
            if (!people.isEmpty() && (full || rnd.nextDouble() < CROSS_SELL)) {
                for (int k = 0; k < 5 && p == null; k++) {
                    Person candidate = people.get(rnd.nextInt(people.size()));
                    if (!candidate.members.containsKey(c) && free(candidate, t, from, to)) {
                        p = candidate;
                    }
                }
            }
            if (p == null) {
                p = register();
            }
            Member m = new Member(memberSeq++, p, c, 0.85 + rnd.nextDouble() * 0.45, t);
            p.members.put(c, m);
            return m;
        }

        private Person register() {
            int n = people.size() + 1;
            if (n > 9999) {
                throw new IllegalStateException("가짜 번호 010-0000-NNNN을 다 썼습니다");
            }
            String name =
                    SURNAMES[rnd.nextInt(SURNAMES.length)]
                            + GIVEN_1[rnd.nextInt(GIVEN_1.length)]
                            + GIVEN_2[rnd.nextInt(GIVEN_2.length)];
            EnumSet<Consent.Item> consents = EnumSet.of(Consent.Item.PRIVACY);
            if (rnd.nextDouble() < 0.7) {
                consents.add(Consent.Item.MARKETING);
                if (rnd.nextDouble() < 0.15) {
                    consents.add(Consent.Item.NIGHT_AD);
                }
            }
            if (rnd.nextDouble() < 0.5) {
                consents.add(Consent.Item.PHOTO);
            }
            if (rnd.nextDouble() < 0.4) {
                consents.add(Consent.Item.HEALTH);
            }
            Consent.Method[] methods = {
                Consent.Method.TABLET, Consent.Method.PAPER, Consent.Method.VERBAL
            };
            double g = rnd.nextDouble();
            Customer.Gender gender =
                    g < 0.9
                            ? Customer.Gender.FEMALE
                            : g < 0.98 ? Customer.Gender.MALE : Customer.Gender.OTHER;
            Customer.PreferredLanguage language =
                    rnd.nextDouble() < 0.96
                            ? Customer.PreferredLanguage.KO
                            : Customer.PreferredLanguage.values()[1 + rnd.nextInt(3)];
            NewCustomer in =
                    new NewCustomer(
                            name,
                            "010-0000-%04d".formatted(n),
                            consents,
                            methods[rnd.nextInt(methods.length)],
                            gender,
                            1970 + rnd.nextInt(36),
                            language,
                            Customer.AcquisitionChannel.values()[
                                    rnd.nextInt(Customer.AcquisitionChannel.values().length)]);
            at(now);
            Person p = new Person(customers.register(shopId, ownerId, in));
            people.add(p);
            peopleById.put(p.customerId, p);
            return p;
        }

        private List<Menu> menusFor(Member m) {
            if (m.cat == Cat.SEMI_PERMANENT) {
                return List.of(m.cat.menus.get(m.retouchNext ? 1 : 0));
            }
            return List.of(weighted(m.cat));
        }

        private Menu weighted(Cat c) {
            int total = c.menus.stream().mapToInt(Menu::weight).sum();
            int r = rnd.nextInt(total);
            for (Menu m : c.menus) {
                r -= m.weight();
                if (r < 0) {
                    return m;
                }
            }
            throw new IllegalStateException();
        }

        /** 예약 경로 분포: 당일은 전화 · 방문, 미리 잡는 예약은 네이버 · 카카오 위주 */
        private ReservationChannel channel(int lead) {
            double r = rnd.nextDouble();
            if (lead == 0) {
                return r < 0.5 ? ReservationChannel.PHONE : ReservationChannel.STORE;
            }
            return r < 0.45
                    ? ReservationChannel.NAVER
                    : r < 0.75
                            ? ReservationChannel.KAKAO
                            : r < 0.95 ? ReservationChannel.PHONE : ReservationChannel.ETC;
        }

        private void create(
                Staff s,
                LocalDate t,
                int slot,
                Member m,
                List<Menu> menuList,
                int lead,
                ReservationChannel channel) {
            Outcome outcome = Outcome.VISIT;
            if (m != null) {
                double r = rnd.nextDouble();
                outcome =
                        lead > 0 && r < 0.025
                                ? Outcome.CANCEL
                                : r < 0.05 ? Outcome.NO_SHOW : Outcome.VISIT;
            }
            LocalDateTime from = slotStart(t, s.cat, slot);
            at(now);
            long id =
                    reservations.create(
                            shopId,
                            new NewReservation(
                                    m == null ? null : m.person.customerId,
                                    s.id,
                                    from,
                                    null,
                                    channel,
                                    false,
                                    null,
                                    menuList.stream().map(menuIds::get).toList()),
                            true);
            Booking b = new Booking(s, slot, t, from, m, menuList, outcome, id);
            s.slots(t)[slot] = b;
            byDate.computeIfAbsent(t, k -> new ArrayList<>()).add(b);
        }

        /** 시술이 끝나는 순서로 마감 · 노쇼를 기록하고, 캠페인 날이면 14:00에 보낸다 */
        private void day(LocalDate d) {
            List<Booking> today = new ArrayList<>(bookings(d));
            today.sort(
                    Comparator.comparing((Booking b) -> b.end)
                            .thenComparingInt(b -> b.staff.index));
            long n = ChronoUnit.DAYS.between(start, d);
            boolean campaign = n >= 2L * CAMPAIGN_EVERY_DAYS && n % CAMPAIGN_EVERY_DAYS == 0;
            LocalDateTime campaignAt = d.atTime(14, 0);
            for (Booking b : today) {
                if (campaign && b.end.isAfter(campaignAt)) {
                    campaign(d);
                    campaign = false;
                }
                at(b.end);
                finish(b);
            }
            if (campaign) {
                campaign(d);
            }
            byDate.remove(d);
            staff.forEach(s -> s.days.remove(d));
        }

        private void finish(Booking b) {
            Member m = b.member;
            if (b.outcome == Outcome.NO_SHOW) {
                reservations.markNoShow(shopId, b.id);
                due.get(m.cat).add(m);
                return;
            }
            int amount = b.menus.stream().mapToInt(Menu::price).sum();
            double r = rnd.nextDouble();
            PaymentMethod method =
                    r < 0.7
                            ? PaymentMethod.CARD
                            : r < 0.9 ? PaymentMethod.TRANSFER : PaymentMethod.CASH;
            List<Closing.Item> items = null;
            String memo = null;
            if (m != null) {
                if (m.cat != Cat.SEMI_PERMANENT && rnd.nextDouble() < 0.02) {
                    // 이번 시술은 주기 계산 제외 (FR-VIS-05)
                    items =
                            b.menus.stream()
                                    .map(x -> new Closing.Item(menuIds.get(x), b.staff.id, true))
                                    .toList();
                }
                if (rnd.nextDouble() < 0.1) {
                    memo = MEMOS[rnd.nextInt(MEMOS.length)];
                }
            }
            visits.close(
                    shopId, b.id, new Closing(items, amount, method, memo, m == null), b.staff.id);
            if (m != null) {
                next(m, b);
            }
        }

        /** 이탈. 캠페인에 반응하거나 빈자리에 다시 올 수 있다 */
        private void sleep(Member m) {
            m.active = false;
            dormant.get(m.cat).add(m);
        }

        /** 다음에 올 날을 정한다. 이탈하면 캠페인에만 반응한다. 일부는 마감하면서 다음 예약을 잡는다 (재예약) */
        private void next(Member m, Booking b) {
            LocalDate d = b.date;
            if ("END_SIGNAL".equals(b.menus.get(0).attribute())) {
                sleep(m); // 젤 제거 = 종료 신호 (E5)
                return;
            }
            if (m.cat == Cat.SEMI_PERMANENT) {
                boolean wasRetouch = m.retouchNext;
                m.retouchNext = false;
                if (!wasRetouch && rnd.nextDouble() < 0.9) {
                    m.retouchNext = true;
                    m.due = d.plusDays(28 + rnd.nextInt(15));
                } else if (rnd.nextDouble() < 0.25) {
                    m.due = d.plusDays(365 + rnd.nextInt(366));
                } else {
                    sleep(m);
                    return;
                }
            } else {
                if (rnd.nextDouble() < m.cat.churn) {
                    sleep(m);
                    return;
                }
                int days = m.cat.cycleMin + rnd.nextInt(m.cat.cycleMax - m.cat.cycleMin + 1);
                m.due = d.plusDays(Math.round(days * m.pace));
            }
            if (rnd.nextDouble() < REBOOK && rebook(m, b)) {
                return;
            }
            due.get(m.cat).add(m);
        }

        private boolean rebook(Member m, Booking b) {
            for (int k = 0; k <= 3; k++) {
                LocalDate t = m.due.plusDays(k);
                if (!t.isAfter(b.date) || !open(t)) {
                    continue;
                }
                Booking[] slots = b.staff.slots(t);
                for (int i = 0; i < slots.length; i++) {
                    LocalDateTime from = slotStart(t, b.staff.cat, i);
                    if (slots[i] == null
                            && free(m.person, t, from, from.plusMinutes(b.staff.cat.slotMinutes))) {
                        create(
                                b.staff,
                                t,
                                i,
                                m,
                                menusFor(m),
                                (int) ChronoUnit.DAYS.between(b.date, t),
                                ReservationChannel.STORE);
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * 카테고리마다 재방문 안내(REVISIT, 마지막 시술이 최소 주기 이상 지난 고객)와 이탈 신호 고객 재방문 유도(WIN_BACK)를 번갈아 보낸다 (대조군
         * 20%). 받은 고객은 대조군보다 먼저 · 더 많이 돌아온다: 기다리던 고객은 예약 순서가 7일 앞당겨지고, 이탈한 고객은 30%(대조군 · 미발송 8%)가
         * 돌아온다. 발송과 결과 갱신을 한 트랜잭션에서 해 다른 컨텍스트의 스케줄러가 대기 행을 가져가지 못하게 한다.
         */
        private void campaign(LocalDate d) {
            at(d.atTime(14, 0));
            int k = campaignCount++;
            Cat c = Cat.values()[(k / 2) % Cat.values().length];
            boolean winBack = k % 2 == 1;
            long id;
            try {
                id =
                        campaigns.create(
                                shopId,
                                "%s %s (%s)"
                                        .formatted(
                                                c.menus.get(0).name(),
                                                winBack ? "이탈 신호 재방문 유도" : "재방문 안내",
                                                d),
                                winBack ? winBackTemplateId : revisitTemplateId,
                                CONTROL_PERCENT,
                                new Selection(
                                        null,
                                        c.name(),
                                        null,
                                        null,
                                        null,
                                        winBack ? PredictionStatus.CHURN_SIGNAL : null,
                                        null,
                                        winBack ? null : d.minusDays(c.cycleMin)));
            } catch (ApiException e) {
                if (e.errorCode() == ErrorCode.INVALID) {
                    return; // 보낼 대상 없음
                }
                throw e;
            }
            at(now);
            campaigns.review(shopId, id, ownerId);
            at(now);
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(s -> {
                        sends.send(shopId, id);
                        VirtualDelivery.deliverPending(context);
                    });
            for (Map<String, Object> row :
                    jdbc.queryForList(
                            "SELECT t.customer_id, t.control_group, m.send_status"
                                    + " FROM campaign_target t LEFT JOIN campaign_message m"
                                    + " ON m.campaign_id = t.campaign_id AND m.customer_id = t.customer_id"
                                    + " WHERE t.campaign_id = ? ORDER BY t.customer_id",
                            id)) {
                Person p = peopleById.get(((Number) row.get("customer_id")).longValue());
                Member m = p == null ? null : p.members.get(c);
                if (m == null) {
                    continue;
                }
                boolean reached = "SENT".equals(row.get("send_status"));
                if (m.active) {
                    // 기다리던 고객(예약 전)만 순서가 당겨진다. TreeSet 순서 값을 바꾸므로 빼고 다시 넣는다
                    if (reached && due.get(c).remove(m)) {
                        m.due = m.due.minusDays(7);
                        due.get(c).add(m);
                    }
                    continue;
                }
                if (rnd.nextDouble() < (reached ? 0.3 : 0.08)) {
                    m.active = true;
                    dormant.get(c).remove(m);
                    m.due = d.plusDays(1 + rnd.nextInt(10));
                    due.get(c).add(m);
                }
            }
        }
    }
}

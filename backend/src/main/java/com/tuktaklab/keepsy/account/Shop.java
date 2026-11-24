package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler.FieldViolation;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.FeatureKey.Access;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매장 (명세서 5장 shop). 로그인 · PIN 잠금, 매장 정보 · 영업시간, 설정값이 모두 이 테이블이라 account 패키지가 맡는다 (2026-11-24 팀 결정,
 * ARCHITECTURE 4장). 설정값 컬럼은 그 기능을 구현할 때 매핑한다. 매핑하지 않은 컬럼은 가입 때 V1의 DB 기본값으로 채워진다 (명세서 2장 공용/PIN
 * 기본값, FR-SET-02 · 03 · 04 · 06).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shop {

    /** 로그인 연속 실패 허용 횟수 (FR-ACC-02, C1) */
    public static final int LOGIN_MAX_FAILURES = 5;

    /** 로그인 잠금 시간 (FR-ACC-02, C1) */
    public static final Duration LOGIN_LOCK = Duration.ofMinutes(15);

    /** PIN 연속 실패 허용 횟수. 매장 단위, 근무자 PIN과 잠긴 기능 PIN을 같이 센다 (FR-ACC-08 · 10, C22) */
    public static final int PIN_MAX_FAILURES = 5;

    /** PIN 입력 차단 시간 (FR-ACC-08, C22) */
    public static final Duration PIN_LOCK = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 이메일, 전역 고유 */
    private String email;

    /** BCrypt (NFR-02) */
    private String passwordHash;

    private String name;

    /** 매장 연락처 (광고 표기, FR-CMP-04) */
    private String phone;

    /** 발신번호 (FR-SET-01) */
    private String senderNumber;

    /** 수신 거부 번호 (FR-SET-01) */
    private String unsubscribeNumber;

    // 요일별 영업시간 (FR-SET-01). 시작 · 종료가 모두 null이면 그 요일은 정기 휴무
    private LocalTime monOpenTime;
    private LocalTime monCloseTime;
    private LocalTime tueOpenTime;
    private LocalTime tueCloseTime;
    private LocalTime wedOpenTime;
    private LocalTime wedCloseTime;
    private LocalTime thuOpenTime;
    private LocalTime thuCloseTime;
    private LocalTime friOpenTime;
    private LocalTime friCloseTime;
    private LocalTime satOpenTime;
    private LocalTime satCloseTime;
    private LocalTime sunOpenTime;
    private LocalTime sunCloseTime;

    /** 잠금 전 연속 실패 횟수 (F14) */
    private int loginFailCount;

    /** 이 시각 전까지 로그인 · 비밀번호 재확인을 막는다 (F14) */
    private LocalDateTime loginLockedUntil;

    /** 차단 전 PIN 연속 실패 횟수 (F14) */
    private int pinFailCount;

    /** 이 시각 전까지 PIN 입력을 막는다 (F14) */
    private LocalDateTime pinLockedUntil;

    /** 귀속 기간(일) (FR-SET-04, 기본 14) */
    private int attributionDays;

    /** 가상 발송 무작위 실패 비율 % (FR-CMP-10, FR-SET-04, 기본 0 F17) */
    private int virtualFailurePercent;

    /** 가상 단가(원). 화면에서 바꾸지 않는다 (FR-CMP-05, 기본 0) */
    private int virtualUnitPrice;

    /** PIN으로 잠근 기능 키, 쉼표로 이음 (명세서 5장 shop, FR-ACC-05). 권한 검사는 FeatureSettingsReader가 요청마다 읽는다 */
    private String pinLockedFeatures;

    /** 명세서 2장 기본값: PIN 6개 (V1 DB 기본값과 같음) */
    static final Set<FeatureKey> DEFAULT_PIN_LOCKED =
            EnumSet.of(
                    FeatureKey.CONTACT_FULL,
                    FeatureKey.CUSTOMER_EDIT,
                    FeatureKey.SALES_ANALYTICS,
                    FeatureKey.PAYMENT_EDIT,
                    FeatureKey.CAMPAIGN_VIEW,
                    FeatureKey.CAMPAIGN_SEND);

    // 카테고리 기본 주기(일). 메뉴 없는 가져온 방문의 예측에 쓴다 (FR-SET-02, E5, 기본값 C16)
    private int nailCycleMinDays;
    private int nailCycleMaxDays;
    private int lashCycleMinDays;
    private int lashCycleMaxDays;
    private int waxingCycleMinDays;
    private int waxingCycleMaxDays;
    private int semiPermanentCycleMinDays;
    private int semiPermanentCycleMaxDays;
    private int etcCycleMinDays;
    private int etcCycleMaxDays;

    /** 시술 카테고리, CONTRACTS 3장 순서 */
    public static final List<String> CATEGORIES =
            List.of("NAIL", "LASH", "WAXING", "SEMI_PERMANENT", "ETC");

    public record CategoryCycle(String category, int minDays, int maxDays) {}

    /** 카테고리 기본 주기 5개, {@link #CATEGORIES} 순서 */
    public List<CategoryCycle> categoryCycles() {
        return List.of(
                new CategoryCycle("NAIL", nailCycleMinDays, nailCycleMaxDays),
                new CategoryCycle("LASH", lashCycleMinDays, lashCycleMaxDays),
                new CategoryCycle("WAXING", waxingCycleMinDays, waxingCycleMaxDays),
                new CategoryCycle(
                        "SEMI_PERMANENT", semiPermanentCycleMinDays, semiPermanentCycleMaxDays),
                new CategoryCycle("ETC", etcCycleMinDays, etcCycleMaxDays));
    }

    /**
     * @return 값이 바뀌었으면 true (예측 재계산 대상, FR-SET-02)
     * @throws ApiException 모르는 카테고리이거나 {@code 1 ≤ minDays ≤ maxDays}가 아니면 {@code invalid}
     */
    boolean changeCategoryCycle(String category, int minDays, int maxDays) {
        if (minDays < 1 || minDays > maxDays) {
            throw new ApiException(ErrorCode.INVALID, "기본 주기는 1일 이상이고 최소가 최대보다 클 수 없습니다");
        }
        boolean changed =
                categoryCycles().stream()
                        .noneMatch(c ->
                                c.category().equals(category)
                                        && c.minDays() == minDays
                                        && c.maxDays() == maxDays);
        switch (category) {
            case "NAIL" -> {
                nailCycleMinDays = minDays;
                nailCycleMaxDays = maxDays;
            }
            case "LASH" -> {
                lashCycleMinDays = minDays;
                lashCycleMaxDays = maxDays;
            }
            case "WAXING" -> {
                waxingCycleMinDays = minDays;
                waxingCycleMaxDays = maxDays;
            }
            case "SEMI_PERMANENT" -> {
                semiPermanentCycleMinDays = minDays;
                semiPermanentCycleMaxDays = maxDays;
            }
            case "ETC" -> {
                etcCycleMinDays = minDays;
                etcCycleMaxDays = maxDays;
            }
            default -> throw new ApiException(ErrorCode.INVALID, "모르는 시술 카테고리입니다");
        }
        return changed;
    }

    /** 한 요일의 영업시간. 정기 휴무면 open · close가 null */
    public record OpeningHours(DayOfWeek dayOfWeek, LocalTime open, LocalTime close) {}

    /** 월요일부터 일요일까지 7개 (CONTRACTS 6장 GET /api/shop, F20). 시작 · 종료 중 하나만 있으면 휴무로 본다 */
    public List<OpeningHours> openingHours() {
        LocalTime[][] times = {
            {monOpenTime, monCloseTime},
            {tueOpenTime, tueCloseTime},
            {wedOpenTime, wedCloseTime},
            {thuOpenTime, thuCloseTime},
            {friOpenTime, friCloseTime},
            {satOpenTime, satCloseTime},
            {sunOpenTime, sunCloseTime}
        };
        return Arrays.stream(DayOfWeek.values())
                .map(day -> {
                    LocalTime[] t = times[day.ordinal()];
                    return t[0] == null || t[1] == null
                            ? new OpeningHours(day, null, null)
                            : new OpeningHours(day, t[0], t[1]);
                })
                .toList();
    }

    static Shop create(String email, String passwordHash, String name) {
        Shop shop = new Shop();
        shop.email = email;
        shop.passwordHash = passwordHash;
        shop.name = name;
        // 매핑한 칸은 INSERT에 모두 들어가 V1의 DB 기본값이 쓰이지 않으므로 같은 값을 넣는다 (FR-SET-02 · C16)
        shop.changeCategoryCycle("NAIL", 21, 28);
        shop.changeCategoryCycle("LASH", 30, 60);
        shop.changeCategoryCycle("WAXING", 28, 42);
        shop.changeCategoryCycle("SEMI_PERMANENT", 365, 730);
        shop.changeCategoryCycle("ETC", 28, 56);
        shop.attributionDays = 14; // FR-SET-04 기본값
        shop.pinLockedFeatures = join(DEFAULT_PIN_LOCKED);
        return shop;
    }

    /** PIN으로 잠근 기능 키 */
    Set<FeatureKey> lockedFeatures() {
        Set<FeatureKey> locked = EnumSet.noneOf(FeatureKey.class);
        Arrays.stream(pinLockedFeatures.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(s -> locked.add(FeatureKey.valueOf(s)));
        return locked;
    }

    /**
     * 보낸 키만 공용/PIN을 바꾼다 (FR-ACC-05, F34). 하나라도 틀리면 아무것도 바꾸지 않는다.
     *
     * @param changes 기능 키 이름 → {@code PUBLIC} | {@code PIN}
     * @throws ApiException 빈 값, 모르는 키 · 값, 고정 키를 다른 값으로, 토글 키에 {@code OWNER_PIN}이면 {@code invalid}
     */
    void changeFeatureLocks(Map<String, String> changes) {
        if (changes.isEmpty()) {
            throw lockInvalid("features", "바꿀 기능을 보내 주세요");
        }
        Set<FeatureKey> locked = lockedFeatures();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            String field = "features." + e.getKey();
            FeatureKey key =
                    parse(FeatureKey.class, e.getKey())
                            .orElseThrow(() -> lockInvalid(field, "모르는 기능 키입니다"));
            Access value =
                    parse(Access.class, e.getValue())
                            .orElseThrow(() -> lockInvalid(field, "PUBLIC 또는 PIN이어야 합니다"));
            if (key.fixed() != null) {
                if (value != key.fixed()) {
                    throw lockInvalid(field, "고정 항목은 바꿀 수 없습니다");
                }
                continue;
            }
            switch (value) {
                case PUBLIC -> locked.remove(key);
                case PIN -> locked.add(key);
                default -> throw lockInvalid(field, "PUBLIC 또는 PIN이어야 합니다");
            }
        }
        pinLockedFeatures = join(locked);
    }

    private static String join(Set<FeatureKey> keys) {
        return keys.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static <E extends Enum<E>> Optional<E> parse(Class<E> type, String name) {
        return Arrays.stream(type.getEnumConstants())
                .filter(c -> c.name().equals(name))
                .findFirst();
    }

    private static ApiException lockInvalid(String field, String reason) {
        return new ApiException(
                ErrorCode.INVALID, reason, List.of(new FieldViolation(field, reason)));
    }

    boolean isLoginLocked(LocalDateTime now) {
        return loginLockedUntil != null && now.isBefore(loginLockedUntil);
    }

    /** 비밀번호가 틀렸을 때. 5회째면 15분 잠그고 횟수를 처음부터 다시 센다 */
    void loginFailed(LocalDateTime now) {
        loginFailCount++;
        if (loginFailCount >= LOGIN_MAX_FAILURES) {
            loginLockedUntil = now.plus(LOGIN_LOCK);
            loginFailCount = 0;
        }
    }

    void loginSucceeded() {
        loginFailCount = 0;
        loginLockedUntil = null;
    }

    boolean isPinLocked(LocalDateTime now) {
        return pinLockedUntil != null && now.isBefore(pinLockedUntil);
    }

    /** PIN이 틀렸을 때. 5회째면 5분 막고 횟수를 처음부터 다시 센다 */
    void pinFailed(LocalDateTime now) {
        pinFailCount++;
        if (pinFailCount >= PIN_MAX_FAILURES) {
            pinLockedUntil = now.plus(PIN_LOCK);
            pinFailCount = 0;
        }
    }

    void pinSucceeded() {
        pinFailCount = 0;
        pinLockedUntil = null;
    }
}

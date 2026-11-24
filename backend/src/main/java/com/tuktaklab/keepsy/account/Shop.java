package com.tuktaklab.keepsy.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매장 (명세서 5장 shop). 설정값 · 영업시간 · PIN 잠금 상태 컬럼은 그 기능을 구현할 때 매핑한다. 매핑하지 않은 컬럼은 가입 때 V1의 DB 기본값으로 채워진다
 * (명세서 2장 공용/PIN 기본값, FR-SET-02 · 03 · 04 · 06).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shop {

    /** 로그인 연속 실패 허용 횟수 (FR-ACC-02, C1) */
    public static final int LOGIN_MAX_FAILURES = 5;

    /** 로그인 잠금 시간 (FR-ACC-02, C1) */
    public static final Duration LOGIN_LOCK = Duration.ofMinutes(15);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 이메일, 전역 고유 */
    private String email;

    /** BCrypt (NFR-02) */
    private String passwordHash;

    private String name;

    /** 잠금 전 연속 실패 횟수 (F14) */
    private int loginFailCount;

    /** 이 시각 전까지 로그인 · 비밀번호 재확인을 막는다 (F14) */
    private LocalDateTime loginLockedUntil;

    static Shop create(String email, String passwordHash, String name) {
        Shop shop = new Shop();
        shop.email = email;
        shop.passwordHash = passwordHash;
        shop.name = name;
        return shop;
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
}

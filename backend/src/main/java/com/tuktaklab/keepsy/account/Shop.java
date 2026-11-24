package com.tuktaklab.keepsy.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매장 (명세서 5장 shop). 설정값 · 영업시간 · 잠금 상태 컬럼은 그 기능을 구현할 때 매핑한다. 매핑하지 않은 컬럼은 가입 때 V1의 DB 기본값으로 채워진다
 * (명세서 2장 공용/PIN 기본값, FR-SET-02 · 03 · 04 · 06).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 이메일, 전역 고유 */
    private String email;

    /** BCrypt (NFR-02) */
    private String passwordHash;

    private String name;

    static Shop create(String email, String passwordHash, String name) {
        Shop shop = new Shop();
        shop.email = email;
        shop.passwordHash = passwordHash;
        shop.name = name;
        return shop;
    }
}

package com.tuktaklab.keepsy.customer;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고객 (명세서 5장 customer). 주의사항 · 취향 메모 · 예약 제한 · 마지막 방문일은 그 기능을 구현할 때 매핑한다. {@code phone_last4}는 DB가
 * 채우는 검색용 칼럼이라 매핑하지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {

    /** CONTRACTS 3장 「성별」 [제안] */
    public enum Gender {
        FEMALE,
        MALE,
        OTHER
    }

    /** CONTRACTS 3장 「선호 언어」 [제안] (FR-I18N-01) */
    public enum PreferredLanguage {
        KO,
        EN,
        ZH,
        JA,
        ETC
    }

    /** CONTRACTS 3장 「유입 경로」 [제안] */
    public enum AcquisitionChannel {
        INSTAGRAM,
        NAVER_SEARCH,
        REFERRAL,
        PASSING_BY,
        ETC
    }

    /** CONTRACTS 3장 「고객 상태」 [제안] (C12) */
    public enum Status {
        ACTIVE,
        DELETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private String name;

    /** {@code 010-0000-0000} 형태. 매장 안 고유, 삭제되면 NULL (C12) */
    private String phone;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private Integer birthYear;

    @Enumerated(EnumType.STRING)
    private PreferredLanguage preferredLanguage;

    @Enumerated(EnumType.STRING)
    private AcquisitionChannel acquisitionChannel;

    private LocalDateTime registeredAt;

    @Enumerated(EnumType.STRING)
    private Status status;

    static Customer register(long shopId, NewCustomer c, LocalDateTime now) {
        Customer customer = new Customer();
        customer.shopId = shopId;
        customer.name = c.name();
        customer.phone = normalizePhone(c.phone());
        customer.gender = c.gender();
        customer.birthYear = c.birthYear();
        customer.preferredLanguage = c.preferredLanguage();
        customer.acquisitionChannel = c.acquisitionChannel();
        customer.registeredAt = now;
        customer.status = Status.ACTIVE;
        return customer;
    }

    /** 형식은 요청 검증({@code 010-?\d{4}-?\d{4}})을 통과한 값. 하이픈 유무와 상관없이 {@code 010-0000-0000}으로 맞춘다 */
    static String normalizePhone(String phone) {
        String d = phone.replace("-", "");
        return d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7);
    }
}

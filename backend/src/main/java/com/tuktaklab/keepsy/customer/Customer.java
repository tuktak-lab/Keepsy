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
 * 고객 (명세서 5장 customer). 예약 제한 · 마지막 방문일은 그 기능을 구현할 때 매핑한다. {@code phone_last4}는 DB가 채우는 검색용 칼럼이라
 * 매핑하지 않는다.
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

    /** 시술 주의사항 (FR-CUS-05) */
    private String serviceCaution;

    /** 건강 주의사항. 건강 정보 동의(HEALTH) 고객만 (FR-CUS-05, D7) */
    private String healthCaution;

    /** 취향 자유 메모 (FR-CUS-04) */
    private String preferenceMemo;

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

    /** 고객 기본 정보 (FR-CUS-01 항목). 수정은 통째로 바꾸므로 빈 선택 칸은 비운다 (CONTRACTS 6장) */
    public record BasicInfo(
            String name,
            String phone,
            Gender gender,
            Integer birthYear,
            PreferredLanguage preferredLanguage,
            AcquisitionChannel acquisitionChannel) {}

    /** 형식은 요청 검증을 통과한 값. 휴대폰은 {@code 010-0000-0000}으로 맞춘다 */
    void changeBasicInfo(BasicInfo b) {
        name = b.name();
        phone = normalizePhone(b.phone());
        gender = b.gender();
        birthYear = b.birthYear();
        preferredLanguage = b.preferredLanguage();
        acquisitionChannel = b.acquisitionChannel();
    }

    void changePreferenceMemo(String memo) {
        preferenceMemo = memo;
    }

    /** 두 칸을 통째로 바꾼다. 건강 정보 동의 확인은 서비스가 먼저 한다 */
    void changeCautions(String service, String health) {
        serviceCaution = service;
        healthCaution = health;
    }

    /** 응답용 마스킹 {@code 010-****-1234} (NFR-04, A4). 연락처 전체는 CONTACT_FULL 권한 API에서만 */
    public static String maskPhone(String phone) {
        return phone == null ? null : phone.substring(0, 4) + "****" + phone.substring(8);
    }

    /** 형식은 요청 검증({@code 010-?\d{4}-?\d{4}})을 통과한 값. 하이픈 유무와 상관없이 {@code 010-0000-0000}으로 맞춘다 */
    static String normalizePhone(String phone) {
        String d = phone.replace("-", "");
        return d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7);
    }
}

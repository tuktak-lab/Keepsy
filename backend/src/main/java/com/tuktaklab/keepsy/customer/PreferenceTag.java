package com.tuktaklab.keepsy.customer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 고객의 카테고리별 취향 태그 (FR-CUS-04, 명세서 5장 preference_tag). 매장 안에서 재사용한다 (F3) */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PreferenceTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private Long customerId;

    /** CONTRACTS 3장 「시술 카테고리」 */
    private String category;

    private String tag;

    static PreferenceTag of(long shopId, long customerId, String category, String tag) {
        PreferenceTag t = new PreferenceTag();
        t.shopId = shopId;
        t.customerId = customerId;
        t.category = category;
        t.tag = tag;
        return t;
    }
}

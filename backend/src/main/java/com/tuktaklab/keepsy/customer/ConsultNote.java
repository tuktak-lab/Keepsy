package com.tuktaklab.keepsy.customer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 상담 메모 (FR-CUS-06, 명세서 5장 consult_note) */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private Long customerId;

    /** 작성 스탭 = 현재 근무자 (FR-ACC-10, D6) */
    private Long staffId;

    private String content;

    private LocalDateTime writtenAt;

    static ConsultNote write(
            long shopId, long customerId, long staffId, String content, LocalDateTime now) {
        ConsultNote n = new ConsultNote();
        n.shopId = shopId;
        n.customerId = customerId;
        n.staffId = staffId;
        n.content = content;
        n.writtenAt = now;
        return n;
    }

    /** 내용만 바꾼다. 작성 스탭 · 작성 시각은 처음 값 그대로 (C5) */
    void edit(String content) {
        this.content = content;
    }
}

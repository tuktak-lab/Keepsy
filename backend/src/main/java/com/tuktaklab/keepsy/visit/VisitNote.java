package com.tuktaklab.keepsy.visit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 방문 메모 (명세서 5장 visit_note, FR-PAY-04, F1). 방문 하나에 여러 개, 마감 카드를 저장할 때마다 새 행 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private Long visitId;

    /** 작성 스탭 (현재 근무자, FR-ACC-10) */
    private Long staffId;

    @Column(columnDefinition = "TEXT")
    private String content;

    private LocalDateTime createdAt;

    static VisitNote of(
            long shopId, long visitId, long staffId, String content, LocalDateTime createdAt) {
        VisitNote n = new VisitNote();
        n.shopId = shopId;
        n.visitId = visitId;
        n.staffId = staffId;
        n.content = content;
        n.createdAt = createdAt;
        return n;
    }
}

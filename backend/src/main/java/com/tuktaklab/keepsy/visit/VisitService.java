package com.tuktaklab.keepsy.visit;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방문의 실제 시술 (명세서 5장 visit_service, A13). 담당 스탭과 귀속은 시술마다 기록한다. 업무 Service 클래스는 {@code
 * VisitAppService}로 이름을 나눈다 (DEVELOPMENT 4장 「이름」).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    /** 이 시술이 온 예약. 가져온 방문이면 null */
    private Long reservationId;

    /** 가져온 방문이면 null (메뉴 미상) */
    private Long serviceMenuId;

    // ponytail: 카테고리는 CONTRACTS 3장 「시술 카테고리」 enum 이름 문자열. 공용 enum이 생기면 그 타입으로 바꾼다
    private String category;

    /** 담당 스탭. 가져온 방문이면 null */
    private Long staffId;

    /** 주기 계산 제외 (FR-VIS-05) */
    private boolean cycleExcluded;

    /** 귀속 발송 campaign_message.id (F15, FR-VIS-04) */
    private Long attributedMessageId;

    VisitService(
            Visit visit,
            Long shopId,
            Long reservationId,
            Long serviceMenuId,
            String category,
            Long staffId) {
        this.visit = visit;
        this.shopId = shopId;
        this.reservationId = reservationId;
        this.serviceMenuId = serviceMenuId;
        this.category = category;
        this.staffId = staffId;
    }
}

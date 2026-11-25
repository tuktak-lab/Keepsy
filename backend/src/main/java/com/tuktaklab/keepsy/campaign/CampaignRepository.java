package com.tuktaklab.keepsy.campaign;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CampaignRepository extends JpaRepository<Campaign, Long> {

    boolean existsByIdAndShopId(long id, long shopId);

    Optional<Campaign> findByIdAndShopId(long id, long shopId);

    /** 템플릿 수정 거부 확인 (F43) */
    boolean existsByShopIdAndTemplateIdAndStatusIn(
            long shopId, long templateId, Collection<Campaign.Status> statuses);

    /** 작성 중일 때만 검토를 기록한다 (FR-CMP-06). 상태 전이와 같이 조건부 UPDATE (F7). @return 바뀐 행 수 */
    @Modifying
    @Query(
            "UPDATE Campaign c SET c.reviewedByStaffId = :staffId, c.reviewedAt = :now"
                    + " WHERE c.id = :id AND c.shopId = :shopId"
                    + " AND c.status = com.tuktaklab.keepsy.campaign.Campaign.Status.DRAFT")
    int review(
            @Param("id") long id,
            @Param("shopId") long shopId,
            @Param("staffId") long staffId,
            @Param("now") LocalDateTime now);
}

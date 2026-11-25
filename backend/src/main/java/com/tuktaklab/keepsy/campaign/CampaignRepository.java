package com.tuktaklab.keepsy.campaign;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    /**
     * 발송 시작: 작성 중 · 예약 발송 대기일 때만 {@code SENDING}으로 바꾸고 발송 시각을 남긴다 (F7). 동시에 두 번 불러도 1을 받는 쪽은 하나뿐이다.
     *
     * @return 바뀐 행 수 (0이면 이미 발송 중 · 완료 · 취소)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE Campaign c SET c.status = com.tuktaklab.keepsy.campaign.Campaign.Status.SENDING,"
                    + " c.sentAt = :now WHERE c.id = :id AND c.shopId = :shopId"
                    + " AND c.status IN (com.tuktaklab.keepsy.campaign.Campaign.Status.DRAFT,"
                    + " com.tuktaklab.keepsy.campaign.Campaign.Status.SCHEDULED)")
    int startSending(
            @Param("id") long id, @Param("shopId") long shopId, @Param("now") LocalDateTime now);

    /** 예약 지정 · 다시 지정: 작성 중 · 예약 발송 대기일 때만 (F56). @return 바뀐 행 수 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE Campaign c SET c.status = com.tuktaklab.keepsy.campaign.Campaign.Status.SCHEDULED,"
                    + " c.scheduledAt = :at WHERE c.id = :id AND c.shopId = :shopId"
                    + " AND c.status IN (com.tuktaklab.keepsy.campaign.Campaign.Status.DRAFT,"
                    + " com.tuktaklab.keepsy.campaign.Campaign.Status.SCHEDULED)")
    int schedule(@Param("id") long id, @Param("shopId") long shopId, @Param("at") LocalDateTime at);

    /** 상태가 {@code from} 중 하나일 때만 취소한다 (F7 · F56). @return 바뀐 행 수 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE Campaign c SET c.status = com.tuktaklab.keepsy.campaign.Campaign.Status.CANCELED,"
                    + " c.cancelReason = :reason, c.canceledAt = :now"
                    + " WHERE c.id = :id AND c.shopId = :shopId AND c.status IN :from")
    int cancel(
            @Param("id") long id,
            @Param("shopId") long shopId,
            @Param("from") Collection<Campaign.Status> from,
            @Param("reason") Campaign.CancelReason reason,
            @Param("now") LocalDateTime now);

    /** 예약 시각이 지난 예약 발송 대기 캠페인 (idx_campaign_status_scheduled, F56) */
    List<Campaign> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAt(
            Campaign.Status status, LocalDateTime now);

    /** 대기(PENDING) 발송 기록이 남지 않은 발송 중 캠페인을 발송 완료로 바꾼다 (F7). 가상 발송기가 결과를 정한 뒤 부른다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE Campaign c SET c.status = com.tuktaklab.keepsy.campaign.Campaign.Status.SENT"
                    + " WHERE c.status = com.tuktaklab.keepsy.campaign.Campaign.Status.SENDING"
                    + " AND NOT EXISTS (SELECT 1 FROM CampaignMessage m WHERE m.campaignId = c.id"
                    + " AND m.sendStatus = com.tuktaklab.keepsy.campaign.SendStatus.PENDING)")
    int finishSent();
}

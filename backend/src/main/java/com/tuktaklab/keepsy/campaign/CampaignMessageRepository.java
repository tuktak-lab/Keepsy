package com.tuktaklab.keepsy.campaign;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CampaignMessageRepository extends JpaRepository<CampaignMessage, Long> {

    /**
     * 지금 보낼 PENDING 발송 기록, 먼저 만든 순 (idx_campaign_message_status). 발송 예정 시각이 남은 행(야간 수신 거부 안내)은 빼고,
     * 그 시각이 지나면 넣는다 (F48). {@code PageRequest.of(0, 500)}으로 한 번에 처리할 수를 정한다
     */
    @Query(
            "SELECT m FROM CampaignMessage m"
                    + " WHERE m.sendStatus = com.tuktaklab.keepsy.campaign.SendStatus.PENDING"
                    + " AND (m.sendAfter IS NULL OR m.sendAfter <= :now) ORDER BY m.id")
    List<CampaignMessage> findDuePending(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 오늘 보낸 건수 (FR-CMP-12, F54): 발송 시각이 [from, to)인 그 매장 캠페인의 발송 기록 중 발송기에 넘긴 것(메시지 유형이 있는 행). 대조군
     * · 발송 시점에 빠진 고객(F51)은 메시지 유형이 없어 빠지고, 수신 거부 안내는 캠페인이 없어 빠진다.
     */
    @Query(
            "SELECT COUNT(m) FROM CampaignMessage m, Campaign c WHERE m.campaignId = c.id"
                    + " AND c.shopId = :shopId AND c.sentAt >= :from AND c.sentAt < :to"
                    + " AND m.messageType IS NOT NULL")
    long countSentBetween(
            @Param("shopId") long shopId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 한 번호의 발송 기록, 받은 시각 최신순 (가상 수신함 FR-CMP-16, idx_campaign_message_shop_phone) */
    Page<CampaignMessage> findByShopIdAndRecipientPhoneAndSendStatusOrderByResultAtDescIdDesc(
            Long shopId, String recipientPhone, SendStatus sendStatus, Pageable pageable);

    /** 고객이 받은 메시지 중 귀속에 쓰는 것 (FR-ANL-03, idx_campaign_message_customer_result) */
    List<CampaignMessage>
            findByShopIdAndCustomerIdAndKindAndSendStatusAndControlGroupFalseAndCategoryNotNull(
                    Long shopId, Long customerId, MessageKind kind, SendStatus sendStatus);

    /**
     * 아직 PENDING인 행만 결과를 채운다 (ARCHITECTURE 7장 「캠페인 발송 상태 전이」, F7). 이미 결과가 난 행이면 0을 돌려준다.
     *
     * @param failureReason SENT면 null
     */
    @Modifying(clearAutomatically = true)
    @Query(
            "UPDATE CampaignMessage m SET m.sendStatus = :status, m.failureReason = :failureReason,"
                    + " m.resultAt = :resultAt WHERE m.id = :id"
                    + " AND m.sendStatus = com.tuktaklab.keepsy.campaign.SendStatus.PENDING")
    int completeIfPending(
            @Param("id") long id,
            @Param("status") SendStatus status,
            @Param("failureReason") String failureReason,
            @Param("resultAt") LocalDateTime resultAt);
}

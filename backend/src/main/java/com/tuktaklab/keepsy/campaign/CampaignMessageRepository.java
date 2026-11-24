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

    /** 결과를 기다리는 발송 기록, 먼저 만든 순 (idx_campaign_message_status) */
    List<CampaignMessage> findTop500BySendStatusOrderByIdAsc(SendStatus sendStatus);

    /** 한 번호의 발송 기록, 받은 시각 최신순 (가상 수신함 FR-CMP-16, idx_campaign_message_shop_phone) */
    Page<CampaignMessage> findByShopIdAndRecipientPhoneAndSendStatusOrderByResultAtDescIdDesc(
            Long shopId, String recipientPhone, SendStatus sendStatus, Pageable pageable);

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

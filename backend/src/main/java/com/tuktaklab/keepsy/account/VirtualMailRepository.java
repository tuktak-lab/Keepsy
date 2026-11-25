package com.tuktaklab.keepsy.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface VirtualMailRepository extends JpaRepository<VirtualMail, Long> {

    /**
     * 그 이메일로 보낸 메일, 최신순 (F59). ponytail: idx_virtual_mail_shop_recipient는 shop_id가 앞이라 이 조회에 안 맞는다.
     * 시연용 소량 데이터라 그대로 두고, 느려지면 (recipient_email, sent_at) 인덱스를 Flyway로 추가
     */
    Page<VirtualMail> findByRecipientEmailOrderBySentAtDescIdDesc(
            String recipientEmail, Pageable pageable);
}

package com.tuktaklab.keepsy.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface VirtualMailRepository extends JpaRepository<VirtualMail, Long> {

    /** 그 매장의 그 이메일로 보낸 메일, 최신순 (idx_virtual_mail_shop_recipient) */
    Page<VirtualMail> findByShopIdAndRecipientEmailOrderBySentAtDescIdDesc(
            Long shopId, String recipientEmail, Pageable pageable);
}

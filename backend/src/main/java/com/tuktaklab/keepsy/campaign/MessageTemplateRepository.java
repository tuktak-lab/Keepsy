package com.tuktaklab.keepsy.campaign;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    Page<MessageTemplate> findByShopIdOrderById(long shopId, Pageable pageable);
}

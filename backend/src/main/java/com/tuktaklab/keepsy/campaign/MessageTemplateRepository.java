package com.tuktaklab.keepsy.campaign;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    Page<MessageTemplate> findByShopIdOrderById(long shopId, Pageable pageable);

    /** 템플릿 행을 {@code SELECT … FOR UPDATE}로 잠근다. 템플릿 수정과 캠페인 만들기의 차례를 정한다 (F43) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MessageTemplate> findForUpdateByIdAndShopId(long id, long shopId);
}

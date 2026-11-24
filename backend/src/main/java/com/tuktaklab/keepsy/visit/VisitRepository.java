package com.tuktaklab.keepsy.visit;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface VisitRepository extends JpaRepository<Visit, Long> {

    /** 방문 삭제 · 수정 때 행 잠금. 같은 방문을 동시에 지우면 하나만 성공한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Visit> findForUpdateByIdAndShopId(long id, long shopId);

    /** 같은 고객 · 같은 날 방문 (C6). 잠금 읽기라 트랜잭션 스냅샷과 상관없이 방금 커밋된 방문도 보인다 (동시 완료에서 방문이 두 건 생기지 않게) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Visit> findByShopIdAndCustomerIdAndVisitDate(
            long shopId, long customerId, LocalDate visitDate);

    /** 그 방문이 아닌 방문 시술이 이미 가진 귀속 발송 ID 중 {@code messageIds}에 있는 것 (첫 방문 판정, FR-VIS-04) */
    @Query(
            "select vs.attributedMessageId from VisitService vs where vs.shopId = :shopId"
                    + " and vs.visit.id <> :visitId and vs.attributedMessageId in :messageIds")
    List<Long> findClaimedMessageIds(long shopId, long visitId, Collection<Long> messageIds);
}

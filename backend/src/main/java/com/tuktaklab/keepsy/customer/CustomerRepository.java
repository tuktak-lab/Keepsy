package com.tuktaklab.keepsy.customer;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByShopIdAndPhone(long shopId, String phone);

    /** CSV 가져오기의 기존 고객 찾기 (F50) */
    List<Customer> findByShopIdAndStatusAndPhoneIn(
            long shopId, Customer.Status status, Collection<String> phones);

    boolean existsByIdAndShopIdAndStatus(long id, long shopId, Customer.Status status);

    boolean existsByShopIdAndPhoneAndIdNot(long shopId, String phone, long id);

    Optional<Customer> findByIdAndShopIdAndStatus(long id, long shopId, Customer.Status status);

    /** 고객 행을 {@code SELECT … FOR UPDATE}로 잠근다. 건강 주의사항 저장과 건강 정보 동의 철회의 차례를 정한다 (D7) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Customer> findForUpdateByIdAndShopIdAndStatus(
            long id, long shopId, Customer.Status status);

    /** 번호로 찾아 잠근다. 같은 번호의 [수신거부]가 겹쳐도 이력 · 안내가 한 번만 생긴다 (FR-CMP-11, F48). 번호는 매장 안에서 고유하다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Customer> findForUpdateByShopIdAndPhoneAndStatus(
            long shopId, String phone, Customer.Status status);
}

package com.tuktaklab.keepsy.account;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface ShopRepository extends JpaRepository<Shop, Long> {

    boolean existsByEmail(String email);

    /**
     * 로그인용: 매장 행을 {@code SELECT … FOR UPDATE}로 잠근다. 잠금 확인 → 비밀번호 검증 → 실패 횟수 갱신을 한 트랜잭션에서 해, 동시에 틀린
     * 요청을 보내도 잠금을 우회하지 못한다 (F14)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Shop> findByEmail(String email);

    /**
     * 매장 행을 바꾸는 경로용: PIN 확인은 로그인과 같은 이유로(F14), 설정 · 정보 · 비밀번호 저장은 전체 칸 UPDATE가 그사이 커밋된 PIN · 로그인 실패
     * 횟수를 옛 값으로 되돌리지 않도록 매장 행을 잠근다 (Keepsy #332). 읽기만 하는 조회는 {@code findById}
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Shop> findForUpdateById(Long id);
}

package com.tuktaklab.keepsy.account;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * 아직 안 썼고 만료 전일 때만 사용 처리한다 (F14). 같은 토큰으로 동시에 요청하면 1을 받는 쪽은 하나뿐이다.
     *
     * @return 바뀐 행 수 (0이면 이미 썼거나 만료)
     */
    @Modifying(clearAutomatically = true)
    @Query(
            "UPDATE PasswordResetToken t SET t.usedAt = :now"
                    + " WHERE t.id = :id AND t.usedAt IS NULL AND t.expiresAt > :now")
    int useIfValid(@Param("id") long id, @Param("now") LocalDateTime now);

    /** 그 매장의 아직 안 쓴 토큰을 모두 쓸 수 없게 한다 (재설정 성공 뒤, F49) */
    @Modifying(clearAutomatically = true)
    @Query(
            "UPDATE PasswordResetToken t SET t.usedAt = :now"
                    + " WHERE t.shopId = :shopId AND t.usedAt IS NULL")
    int invalidateUnused(@Param("shopId") long shopId, @Param("now") LocalDateTime now);
}

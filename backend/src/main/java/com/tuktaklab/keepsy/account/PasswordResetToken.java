package com.tuktaklab.keepsy.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 비밀번호 재설정 토큰 (명세서 5장 password_reset_token, F14). 원문은 저장하지 않고 해시만 둔다. 사용은 조건부 UPDATE로 한 번만 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    /** SHA-256 hex */
    private String tokenHash;

    private LocalDateTime expiresAt;

    /** 사용 · 무효 처리 시각. null일 때만 쓸 수 있다 */
    private LocalDateTime usedAt;

    static PasswordResetToken issue(long shopId, String tokenHash, LocalDateTime expiresAt) {
        PasswordResetToken t = new PasswordResetToken();
        t.shopId = shopId;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        return t;
    }
}

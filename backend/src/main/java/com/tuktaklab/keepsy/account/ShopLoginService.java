package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매장 로그인과 실패 잠금 (FR-ACC-02, C1, F14) */
@Service
public class ShopLoginService {

    private final ShopRepository shopRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /** 없는 이메일에도 같은 시간만큼 비밀번호를 비교해, 응답 시간으로 가입 여부를 알 수 없게 한다 */
    private final String dummyHash;

    public ShopLoginService(
            ShopRepository shopRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.shopRepository = shopRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("keepsy-dummy-password");
    }

    public record LoggedIn(long shopId, String shopName) {}

    /**
     * 실패해도 실패 횟수는 저장해야 하므로 업무 예외로 롤백하지 않는다.
     *
     * @throws ApiException 이메일이 없거나 비밀번호가 틀리면 {@code login_failed}(어느 쪽인지 말하지 않음), 잠금 중이거나 이번 실패로
     *     잠기면 {@code login_locked}
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoggedIn login(String email, String password) {
        Shop shop = shopRepository.findByEmail(email).orElse(null);
        if (shop == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new ApiException(ErrorCode.LOGIN_FAILED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (shop.isLoginLocked(now)) {
            throw new ApiException(ErrorCode.LOGIN_LOCKED);
        }
        if (!passwordEncoder.matches(password, shop.getPasswordHash())) {
            shop.loginFailed(now);
            throw new ApiException(
                    shop.isLoginLocked(now) ? ErrorCode.LOGIN_LOCKED : ErrorCode.LOGIN_FAILED);
        }
        shop.loginSucceeded();
        return new LoggedIn(shop.getId(), shop.getName());
    }
}

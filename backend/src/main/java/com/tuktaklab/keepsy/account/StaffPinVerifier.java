package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.ShopScope;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스탭 PIN 확인과 매장 단위 실패 차단 (FR-ACC-08 · 10, C21 · C22, F14). 근무자 전환과 잠긴 기능 PIN 인증이 같이 쓰고 실패 횟수도 같이
 * 센다.
 */
@Service
@RequiredArgsConstructor
public class StaffPinVerifier {

    private final ShopRepository shopRepository;
    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * PIN은 해시라 매장 활성 스탭의 해시와 하나씩 비교한다 (ARCHITECTURE 5장 「PIN 중복 검사」). 매장 행을 잠근 한 트랜잭션에서 차단 확인 → 비교 →
     * 횟수 갱신을 하고, 실패해도 횟수는 저장한다.
     *
     * @return PIN이 맞는 활성 스탭
     * @throws ApiException 맞는 활성 스탭이 없으면 {@code pin_invalid}, 차단 중이거나 이번 실패로 차단되면 {@code
     *     pin_locked}
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Staff verify(long shopId, String pin) {
        Shop shop = ShopScope.orNotFound(shopRepository.findForUpdateById(shopId));
        LocalDateTime now = LocalDateTime.now(clock);
        if (shop.isPinLocked(now)) {
            throw new ApiException(ErrorCode.PIN_LOCKED);
        }
        for (Staff staff : staffRepository.findByShopIdAndStatus(shopId, StaffStatus.ACTIVE)) {
            if (passwordEncoder.matches(pin, staff.getPinHash())) {
                shop.pinSucceeded();
                return staff;
            }
        }
        shop.pinFailed(now);
        throw new ApiException(
                shop.isPinLocked(now) ? ErrorCode.PIN_LOCKED : ErrorCode.PIN_INVALID);
    }
}

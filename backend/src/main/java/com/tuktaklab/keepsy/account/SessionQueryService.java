package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.FeatureKey.Access;
import com.tuktaklab.keepsy.common.security.ShopScope;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 조회 (FR-ACC-08 · 10, CONTRACTS 6장). 세션 값을 읽기만 하고 PIN 만료를 연장하지 않는다 (연장은 잠긴 기능을 쓸 때, ARCHITECTURE
 * 6장).
 */
@Service
@RequiredArgsConstructor
public class SessionQueryService {

    private final ShopSession shopSession;
    private final ShopRepository shopRepository;
    private final StaffRepository staffRepository;
    private final FeatureGuard featureGuard;
    private final Clock clock;

    public record StaffRef(long id, String name) {}

    public record SessionView(
            long shopId,
            String shopName,
            StaffRef currentStaff,
            StaffRef pinStaff,
            OffsetDateTime pinExpiresAt,
            Map<FeatureKey, Access> features) {}

    @Transactional(readOnly = true)
    public SessionView current(HttpSession session) {
        long shopId = shopSession.shopId();
        Shop shop = ShopScope.orNotFound(shopRepository.findById(shopId));

        LocalDateTime pinExpiresAt = shopSession.pinExpiresAt(session);
        boolean pinValid = pinExpiresAt != null && pinExpiresAt.isAfter(LocalDateTime.now(clock));
        StaffRef pinStaff = pinValid ? staff(shopId, shopSession.pinStaffId(session)) : null;

        return new SessionView(
                shopId,
                shop.getName(),
                staff(shopId, shopSession.currentStaffId(session)),
                pinStaff,
                pinStaff == null ? null : TimeConfig.offset(pinExpiresAt),
                featureGuard.accessByFeature(shopId));
    }

    private StaffRef staff(long shopId, Long staffId) {
        if (staffId == null) {
            return null;
        }
        return staffRepository
                .findByIdAndShopId(staffId, shopId)
                .map(s -> new StaffRef(s.getId(), s.getName()))
                .orElse(null);
    }
}

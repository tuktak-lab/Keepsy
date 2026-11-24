package com.tuktaklab.keepsy.common.security;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.FeatureKey.Access;
import jakarta.servlet.http.HttpSession;
import java.lang.annotation.Annotation;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/**
 * 기능별 공용/PIN 권한 검사 (명세서 2장, NFR-04, ARCHITECTURE 6장 「검사 순서」). {@link RequireFeature} · {@link
 * RequireWorker}가 붙은 API 앞에서 {@link FeatureGuardConfig}의 인터셉터가 부른다. 매장 세션은 SecurityConfig가 먼저 확인한다.
 *
 * <ol>
 *   <li>현재 근무자가 없으면 {@code worker_required} 403
 *   <li>기능 설정이 공용이면 통과
 *   <li>PIN이면 PIN 인증이 유효할 때 통과하고 만료를 5분 연장, 아니면 {@code pin_required} 403
 *   <li>원장 PIN이면 PIN 인증 스탭이 원장일 때 통과, 아니면 {@code owner_pin_required} 403
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FeatureGuard {

    /** PIN 인증 유지 시간 (FR-ACC-08, C23) */
    public static final Duration PIN_TIMEOUT = Duration.ofMinutes(5);

    private final ShopSession shopSession;
    private final FeatureSettingsReader settings;
    private final Clock clock;

    /** 컨트롤러 메서드 · 클래스에 붙은 애너테이션대로 검사한다. 아무것도 없으면 매장 세션만으로 통과 */
    void check(HttpSession session, HandlerMethod method) {
        RequireFeature feature = annotation(method, RequireFeature.class);
        if (feature != null || annotation(method, RequireWorker.class) != null) {
            check(session, feature == null ? null : feature.value());
        }
    }

    /**
     * 기능 키 하나를 검사한다. 애너테이션 검사 외에, 조건에 따라 두 번째 기능 키가 필요한 API(예: 방문 삭제에 취소할 결제가 있으면 {@code
     * PAYMENT_EDIT}, CONTRACTS 4장)가 서비스 판단 뒤에 부른다. 통과하면 PIN 만료를 연장한다.
     *
     * @param feature null이면 근무자만 확인
     */
    public void check(HttpSession session, FeatureKey feature) {
        Long shopId = shopSession.shopId();
        if (shopId == null || session == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        Long workerId = shopSession.currentStaffId(session);
        // 비활성화된 스탭이 세션에 남아 있으면 근무자가 없는 것으로 본다 (F34)
        if (workerId == null || settings.isDeactivated(shopId, workerId)) {
            throw new ApiException(ErrorCode.WORKER_REQUIRED);
        }
        if (feature == null) {
            return;
        }
        Access access = access(shopId, feature);
        if (access == Access.PUBLIC) {
            return;
        }
        Long pinStaffId = validPinStaff(session);
        if (access == Access.PIN && pinStaffId == null) {
            throw new ApiException(ErrorCode.PIN_REQUIRED);
        }
        if (access == Access.OWNER_PIN
                && (pinStaffId == null || !settings.isOwner(shopId, pinStaffId))) {
            throw new ApiException(ErrorCode.OWNER_PIN_REQUIRED);
        }
        shopSession.setPin(session, pinStaffId, now().plus(PIN_TIMEOUT));
    }

    /**
     * 기록에 남길 스탭 (ARCHITECTURE 6장 「기록에 남는 사람」): 잠긴 기능으로 한 일은 PIN 인증 스탭, 공용 기능은 현재 근무자. 검사를 통과한 요청
     * 안에서 부른다.
     */
    public Long actorStaffId(HttpSession session, FeatureKey feature) {
        return access(shopSession.shopId(), feature) == Access.PUBLIC
                ? shopSession.currentStaffId(session)
                : shopSession.pinStaffId(session);
    }

    /**
     * 원장 PIN 인증이 유효한지. 조건에 따라서만 원장 PIN이 필요한 API(예약 제한 고객 예약, FR-CUS-14)가 쓴다. 예외를 던지지 않고, 통과하면
     * {@link #check}처럼 PIN 만료를 연장한다.
     */
    public boolean ownerPinVerified(HttpSession session) {
        Long pinStaffId = validPinStaff(session);
        if (pinStaffId == null || !settings.isOwner(shopSession.shopId(), pinStaffId)) {
            return false;
        }
        shopSession.setPin(session, pinStaffId, now().plus(PIN_TIMEOUT));
        return true;
    }

    /** 매장의 기능별 공용/PIN 설정 전부. 화면이 잠긴 영역을 가릴 때 쓴다 (GET /api/session). 검사와 같은 계산이다 */
    public Map<FeatureKey, Access> accessByFeature(long shopId) {
        Set<FeatureKey> locked = settings.lockedFeatures(shopId);
        Map<FeatureKey, Access> result = new EnumMap<>(FeatureKey.class);
        for (FeatureKey feature : FeatureKey.values()) {
            result.put(feature, resolve(feature, locked));
        }
        return result;
    }

    private Access access(long shopId, FeatureKey feature) {
        return resolve(feature, settings.lockedFeatures(shopId));
    }

    private static Access resolve(FeatureKey feature, Set<FeatureKey> locked) {
        if (feature.fixed() != null) {
            return feature.fixed();
        }
        return locked.contains(feature) ? Access.PIN : Access.PUBLIC;
    }

    private Long validPinStaff(HttpSession session) {
        Long staffId = shopSession.pinStaffId(session);
        LocalDateTime expiresAt = shopSession.pinExpiresAt(session);
        // 비활성화된 스탭의 PIN 인증은 만료된 것으로 본다 (F34)
        return staffId != null
                        && expiresAt != null
                        && expiresAt.isAfter(now())
                        && !settings.isDeactivated(shopSession.shopId(), staffId)
                ? staffId
                : null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static <A extends Annotation> A annotation(HandlerMethod method, Class<A> type) {
        A onMethod = method.getMethodAnnotation(type);
        return onMethod != null ? onMethod : method.getBeanType().getAnnotation(type);
    }
}

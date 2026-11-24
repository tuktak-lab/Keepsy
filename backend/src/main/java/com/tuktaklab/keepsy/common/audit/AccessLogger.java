package com.tuktaklab.keepsy.common.audit;

import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 개인정보 접근 로그 (NFR-06, D2, CONTRACTS 1장 [확정]). 개인정보를 조회 · 수정 · 삭제 · 다운로드한 API가 성공한 뒤 한 줄로 부른다.
 *
 * <pre>{@code
 * accessLogger.record(FeatureKey.CUSTOMER_VIEW, AccessLogger.CUSTOMER, customerId, Action.VIEW);
 * }</pre>
 *
 * <p>대상은 유형과 ID만 남긴다. 연락처 · 건강 정보 같은 내용은 넣지 않는다. 기록 스탭은 그 API의 기능 키로 정한다: 공용이면 현재 근무자, 잠긴 기능이면 PIN
 * 인증 스탭 (ARCHITECTURE 6장 「기록에 남는 사람」, FR-ACC-10). 부르는 쪽 트랜잭션이 있으면 함께 커밋된다.
 */
@Component
@RequiredArgsConstructor
public class AccessLogger {

    /** 대상 유형: 고객 */
    public static final String CUSTOMER = "CUSTOMER";

    /** 보관 기간 (D2) */
    static final int RETENTION_YEARS = 2;

    /** NFR-06 조회 · 수정 · 삭제 · 다운로드 */
    public enum Action {
        VIEW,
        UPDATE,
        DELETE,
        DOWNLOAD
    }

    private final JdbcTemplate jdbc;
    private final ShopSession shopSession;
    private final FeatureGuard featureGuard;
    private final Clock clock;

    /**
     * @param feature 그 API의 기능 키 ({@code @RequireFeature}와 같은 값). 기록 스탭을 정한다
     */
    public void record(FeatureKey feature, String targetType, long targetId, Action action) {
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                        .getRequest();
        jdbc.update(
                "INSERT INTO access_log (shop_id, staff_id, target_type, target_id, action, accessed_at, ip)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                shopSession.shopId(),
                featureGuard.actorStaffId(request.getSession(false), feature),
                targetType,
                targetId,
                action.name(),
                LocalDateTime.now(clock),
                // 로컬 실행은 Next.js rewrites를 거쳐 127.0.0.1로 남는다 (배포 없음)
                request.getRemoteAddr());
    }

    /** 2년 지난 행을 매일 새벽에 지운다 (D2). 스케줄링은 CampaignSchedulingConfig의 @EnableScheduling이 앱 전체에 켠다 */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void purgeExpired() {
        jdbc.update(
                "DELETE FROM access_log WHERE accessed_at < ?",
                LocalDateTime.now(clock).minusYears(RETENTION_YEARS));
    }
}

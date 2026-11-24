package com.tuktaklab.keepsy.common.security;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 권한 검사에 필요한 매장 설정 읽기. 캐시하지 않고 요청마다 읽어 토글 변경이 바로 반영된다 (FR-ACC-05, CONTRACTS 1장).
 *
 * <p>ponytail: 매장 · 스탭 엔티티가 아직 없어 V1 테이블을 JdbcTemplate으로 직접 읽는다. 계정 도메인에 조회 Service가 생기면 그것을 부르게
 * 바꾼다 (DEVELOPMENT 4장 「도메인 연동」)
 */
@Component
@RequiredArgsConstructor
public class FeatureSettingsReader {

    private final JdbcTemplate jdbc;

    /** 매장에서 PIN으로 잠근 기능 (명세서 5장 shop 「PIN 잠금 기능 목록」) */
    public Set<FeatureKey> lockedFeatures(long shopId) {
        String csv =
                jdbc.queryForObject(
                        "SELECT pin_locked_features FROM shop WHERE id = ?", String.class, shopId);
        Set<FeatureKey> locked = EnumSet.noneOf(FeatureKey.class);
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(s -> locked.add(FeatureKey.valueOf(s)));
        return locked;
    }

    /** 그 매장의 활성 원장 스탭인지 */
    public boolean isOwner(long shopId, long staffId) {
        List<Boolean> owner =
                jdbc.queryForList(
                        "SELECT owner FROM staff WHERE id = ? AND shop_id = ? AND status = 'ACTIVE'",
                        Boolean.class,
                        staffId,
                        shopId);
        return !owner.isEmpty() && Boolean.TRUE.equals(owner.get(0));
    }

    /** 그 매장의 비활성 스탭인지 (FR-ACC-04, F33). 세션에 남은 스탭을 다음 요청부터 막는 데 쓴다 */
    public boolean isDeactivated(long shopId, long staffId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM staff WHERE id = ? AND shop_id = ? AND status = 'INACTIVE'",
                        Integer.class,
                        staffId,
                        shopId);
        return count != null && count > 0;
    }
}

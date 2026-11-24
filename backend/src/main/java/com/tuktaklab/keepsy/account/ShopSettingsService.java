package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.Shop.CategoryCycle;
import com.tuktaklab.keepsy.account.port.PredictionRecalculation;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.ShopScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 설정값 (FR-SET-04, F21: shop 테이블은 account가 맡는다). 다른 도메인은 이 공개 메서드로 읽는다 (DEVELOPMENT 4장 「도메인 연동」).
 * 설정 화면의 조회 · 수정(#33)도 여기에 더한다.
 */
@Service
@RequiredArgsConstructor
public class ShopSettingsService {

    private final ShopRepository shopRepository;
    private final PredictionRecalculation predictions;

    /**
     * @param virtualFailurePercent 가상 발송 무작위 실패 비율 % (FR-CMP-10)
     * @param virtualUnitPrice 가상 단가(원) (FR-CMP-05)
     */
    public record SendSettings(int virtualFailurePercent, int virtualUnitPrice) {}

    /**
     * @throws com.tuktaklab.keepsy.common.error.ApiException 매장이 없으면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public SendSettings sendSettings(long shopId) {
        Shop shop = ShopScope.orNotFound(shopRepository.findById(shopId));
        return new SendSettings(shop.getVirtualFailurePercent(), shop.getVirtualUnitPrice());
    }

    /** 귀속 기간(일) (FR-SET-04, FR-ANL-03). 예약 · 방문의 캠페인 귀속이 읽는다 */
    @Transactional(readOnly = true)
    public int attributionDays(long shopId) {
        return ShopScope.orNotFound(shopRepository.findById(shopId)).getAttributionDays();
    }

    /**
     * 기능별 공용/PIN을 보낸 키만 바꾼다 (FR-ACC-05, F34). 권한 검사가 요청마다 이 칸을 읽으므로 커밋 직후 요청부터 반영된다.
     *
     * @throws ApiException 규칙을 어기면 {@code invalid} ({@link Shop#changeFeatureLocks})
     */
    @Transactional
    public void updateFeatureLocks(long shopId, Map<String, String> changes) {
        ShopScope.orNotFound(shopRepository.findById(shopId)).changeFeatureLocks(changes);
    }

    /** 카테고리 기본 주기 5개, CONTRACTS 3장 순서 (FR-SET-02) */
    @Transactional(readOnly = true)
    public List<CategoryCycle> categoryCycles(long shopId) {
        return ShopScope.orNotFound(shopRepository.findById(shopId)).categoryCycles();
    }

    /**
     * 카테고리 기본 주기를 통째로 바꾸고, 값이 바뀐 카테고리는 그 카테고리에 메뉴 없는 방문이 있는 고객의 예측을 같은 트랜잭션에서 다시 계산한다 (FR-SET-02,
     * FR-PRD-01).
     *
     * @throws ApiException 5개 카테고리가 한 번씩 있지 않거나 {@code 1 ≤ minDays ≤ maxDays}가 아니면 {@code invalid}
     */
    @Transactional
    public void updateCategoryCycles(long shopId, List<CategoryCycle> cycles) {
        if (cycles.size() != Shop.CATEGORIES.size()
                || !cycles.stream()
                        .map(CategoryCycle::category)
                        .collect(Collectors.toSet())
                        .equals(Set.copyOf(Shop.CATEGORIES))) {
            throw new ApiException(ErrorCode.INVALID, "카테고리 5개를 한 번씩 보내 주세요");
        }
        Shop shop = ShopScope.orNotFound(shopRepository.findById(shopId));
        List<String> changed = new ArrayList<>();
        for (CategoryCycle c : cycles) {
            if (shop.changeCategoryCycle(c.category(), c.minDays(), c.maxDays())) {
                changed.add(c.category());
            }
        }
        // 예측 재계산은 shop 표의 기본 주기를 SQL로 읽으므로 먼저 반영한다
        shopRepository.flush();
        changed.forEach(category -> predictions.categoryDefaultChanged(shopId, category));
    }
}

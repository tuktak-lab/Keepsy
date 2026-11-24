package com.tuktaklab.keepsy.common.security;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.util.Optional;

/**
 * 매장 격리 규칙 (NFR-03, CONTRACTS 1 · 3장, ARCHITECTURE 5장 「구현 메모」 매장 격리).
 *
 * <ol>
 *   <li>경로 · 쿼리 · 본문으로 매장 ID를 받지 않는다. 매장은 항상 {@link ShopSession#shopId()}
 *   <li>Repository 조회 조건에는 항상 {@code shopId}를 넣는다. ID로 하나를 찾을 때는 {@code findByIdAndShopId(id,
 *       shopId)}처럼 만든다
 *   <li>없거나 다른 매장 것이면 둘을 구분하지 않고 {@code not_found} 404. 다른 매장 데이터가 있다는 사실을 드러내지 않는다
 * </ol>
 *
 * <pre>{@code
 * Customer customer = ShopScope.orNotFound(customerRepository.findByIdAndShopId(id, shopSession.shopId()));
 * }</pre>
 */
public final class ShopScope {

    private ShopScope() {}

    /** 세션 매장 조건으로 찾은 결과. 비어 있으면 {@code not_found} 404 */
    public static <T> T orNotFound(Optional<T> foundInSessionShop) {
        return foundInSessionShop.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}

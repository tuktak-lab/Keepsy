package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.Shop.OpeningHours;
import com.tuktaklab.keepsy.account.port.StaffReservations;
import com.tuktaklab.keepsy.account.port.StaffReservations.Timed;
import com.tuktaklab.keepsy.common.security.ShopScope;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 정보 · 영업시간 조회 · 수정 (FR-SET-01 · 07). 매장 설정 화면과 예약 화면이 쓰고, 다른 도메인도 매장 정보가 필요하면 이 공개 메서드를 부른다
 * (DEVELOPMENT 4장 「도메인 연동」).
 */
@Service
@RequiredArgsConstructor
public class ShopInfoService {

    private final ShopRepository shopRepository;
    private final StaffReservations reservations;
    private final Clock clock;

    public record ShopInfo(
            String name,
            String phone,
            String senderNumber,
            String unsubscribeNumber,
            List<OpeningHours> openingHours) {}

    /**
     * @throws com.tuktaklab.keepsy.common.error.ApiException 매장이 없으면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public ShopInfo get(long shopId) {
        Shop shop = ShopScope.orNotFound(shopRepository.findById(shopId));
        return new ShopInfo(
                shop.getName(),
                shop.getPhone(),
                shop.getSenderNumber(),
                shop.getUnsubscribeNumber(),
                shop.openingHours());
    }

    /**
     * 매장 정보 · 영업시간을 통째로 바꾸고, 바꾼 영업시간 밖으로 밀린 확정 예약(지금 이후)을 돌려준다. 예약은 자동 취소하지 않는다 (FR-SET-01 · 07,
     * C7, F12, F39).
     *
     * @throws com.tuktaklab.keepsy.common.error.ApiException 규칙을 어기면 {@code invalid} ({@link
     *     Shop#changeInfo})
     */
    @Transactional
    public List<Timed> update(long shopId, ShopInfo in) {
        Shop shop = ShopScope.orNotFound(shopRepository.findById(shopId));
        shop.changeInfo(
                in.name(),
                in.phone(),
                in.senderNumber(),
                in.unsubscribeNumber(),
                in.openingHours());
        // ponytail: 예약 등록은 매장 행을 잠그지 않고 영업시간을 읽어, 이 저장과 동시에 들어온 예약은 목록에서 빠질 수 있다. 원장이 영업시간을
        // 바꾸는 일은 드물어 미루고, 문제가 되면 매장 행 잠금(F14 방식)을 양쪽에 건다
        return reservations.shopConfirmedFrom(shopId, LocalDateTime.now(clock)).stream()
                .filter(r -> !shop.withinOpeningHours(r.startAt(), r.endAt()))
                .toList();
    }
}

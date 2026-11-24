package com.tuktaklab.keepsy.settings;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.RequireWorker;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.settings.ServiceMenuService.MenuChanges;
import com.tuktaklab.keepsy.settings.ServiceMenuService.NewMenu;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 시술 메뉴 (CONTRACTS 4 · 6장 SET [제안]) */
@RestController
@RequiredArgsConstructor
class ServiceMenuController {

    private static final String CATEGORY = "NAIL|LASH|WAXING|SEMI_PERMANENT|ETC";
    private static final String ATTRIBUTE = "NORMAL|EXCLUDE|END_SIGNAL";

    private final ServiceMenuService menuService;
    private final ShopSession shopSession;

    record MenuItem(
            long id,
            String category,
            String name,
            int price,
            int durationMinutes,
            int cycleMinDays,
            int cycleMaxDays,
            String predictionAttribute) {}

    record CreateRequest(
            @NotNull(message = "카테고리를 고르세요") @Pattern(regexp = CATEGORY, message = "모르는 시술 카테고리입니다")
                    String category,
            @NotBlank(message = "시술명을 입력해 주세요") @Size(max = 100, message = "시술명은 100자 이하입니다")
                    String name,
            @NotNull(message = "가격을 입력해 주세요") @Min(value = 0, message = "가격은 0원 이상입니다")
                    Integer price,
            @NotNull(message = "소요 시간을 입력해 주세요") @Min(value = 30, message = "소요 시간은 30분 이상입니다")
                    Integer durationMinutes,
            @NotNull(message = "기본 주기 최소를 입력해 주세요") @Min(value = 1, message = "기본 주기는 1일 이상입니다")
                    Integer cycleMinDays,
            @NotNull(message = "기본 주기 최대를 입력해 주세요") @Min(value = 1, message = "기본 주기는 1일 이상입니다")
                    Integer cycleMaxDays,
            @Pattern(regexp = ATTRIBUTE, message = "모르는 예측 속성입니다") String predictionAttribute) {}

    /** 보내지 않은 칸(null)은 바꾸지 않는다. 카테고리는 바꿀 수 없다 (F31) */
    record PatchRequest(
            @Null(message = "카테고리는 바꿀 수 없습니다. 다른 카테고리는 새 메뉴로 추가해 주세요") String category,
            @Size(max = 100, message = "시술명은 100자 이하입니다")
                    @Pattern(regexp = "(?s).*\\S.*", message = "시술명을 입력해 주세요")
                    String name,
            @Min(value = 0, message = "가격은 0원 이상입니다") Integer price,
            @Min(value = 30, message = "소요 시간은 30분 이상입니다") Integer durationMinutes,
            @Min(value = 1, message = "기본 주기는 1일 이상입니다") Integer cycleMinDays,
            @Min(value = 1, message = "기본 주기는 1일 이상입니다") Integer cycleMaxDays,
            @Pattern(regexp = ATTRIBUTE, message = "모르는 예측 속성입니다") String predictionAttribute) {}

    record IdResponse(long id) {}

    /** 시술 메뉴 (FR-SET-02). 근무자. 예약 화면이 소요 시간 합계를 기본값으로 쓴다 (FR-RSV-01) */
    @RequireWorker
    @GetMapping("/api/service-menus")
    List<MenuItem> menus() {
        return menuService.menus(shopSession.shopId()).stream()
                .map(m ->
                        new MenuItem(
                                m.getId(),
                                m.getCategory(),
                                m.getName(),
                                m.getPrice(),
                                m.getDurationMinutes(),
                                m.getCycleMinDays(),
                                m.getCycleMaxDays(),
                                m.getPredictionAttribute()))
                .toList();
    }

    /** 메뉴 추가 (FR-SET-02). 원장 PIN */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PostMapping("/api/service-menus")
    @ResponseStatus(HttpStatus.CREATED)
    IdResponse create(@Valid @RequestBody CreateRequest req) {
        long id =
                menuService.create(
                        shopSession.shopId(),
                        new NewMenu(
                                req.category(),
                                req.name(),
                                req.price(),
                                req.durationMinutes(),
                                req.cycleMinDays(),
                                req.cycleMaxDays(),
                                Objects.requireNonNullElse(req.predictionAttribute(), "NORMAL")));
        return new IdResponse(id);
    }

    /** 메뉴 수정 (FR-SET-02, FR-VIS-05). 원장 PIN. 기본 주기 · 예측 속성이 바뀌면 예측을 다시 계산한다 */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PatchMapping("/api/service-menus/{menuId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@PathVariable long menuId, @Valid @RequestBody PatchRequest req) {
        menuService.update(
                shopSession.shopId(),
                menuId,
                new MenuChanges(
                        req.name(),
                        req.price(),
                        req.durationMinutes(),
                        req.cycleMinDays(),
                        req.cycleMaxDays(),
                        req.predictionAttribute()));
    }
}

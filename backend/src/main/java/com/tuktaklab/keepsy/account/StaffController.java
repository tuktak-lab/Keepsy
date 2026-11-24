package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.StaffCommandService.NewStaff;
import com.tuktaklab.keepsy.account.StaffCommandService.StaffChanges;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.RequireWorker;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 스탭 목록 · 등록 · 수정 (CONTRACTS 4 · 6장 ACC [제안]). PIN 해시는 어느 응답에도 넣지 않는다 */
@RestController
@RequiredArgsConstructor
class StaffController {

    private final StaffQueryService queryService;
    private final StaffCommandService commandService;
    private final ShopSession shopSession;

    record CreateRequest(
            @NotBlank(message = "이름을 입력해 주세요") @Size(max = 50, message = "이름은 50자 이하입니다")
                    String name,
            @NotNull(message = "PIN을 입력해 주세요")
                    @Pattern(regexp = "\\d{4,6}", message = "PIN은 숫자 4~6자리입니다")
                    String pin,
            Boolean lockedFeatureAllowed,
            List<@NotNull DayOfWeek> regularDaysOff) {}

    /** 보내지 않은 칸(null)은 바꾸지 않는다 */
    record PatchRequest(
            @Size(max = 50, message = "이름은 50자 이하입니다")
                    @Pattern(regexp = ".*\\S.*", message = "이름을 입력해 주세요")
                    String name,
            Boolean lockedFeatureAllowed,
            List<@NotNull DayOfWeek> regularDaysOff) {}

    record PinRequest(
            @NotNull(message = "PIN을 입력해 주세요")
                    @Pattern(regexp = "\\d{4,6}", message = "PIN은 숫자 4~6자리입니다")
                    String pin) {}

    record IdResponse(long id) {}

    record PushedReservation(long id, OffsetDateTime startAt, String customerName) {}

    record PatchResponse(List<PushedReservation> pushedReservations) {}

    /** 스탭 등록 (FR-ACC-03). 원장 PIN */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PostMapping("/api/staff")
    @ResponseStatus(HttpStatus.CREATED)
    IdResponse create(@Valid @RequestBody CreateRequest req) {
        long id =
                commandService.create(
                        shopSession.shopId(),
                        new NewStaff(
                                req.name(),
                                req.pin(),
                                Boolean.TRUE.equals(req.lockedFeatureAllowed()),
                                req.regularDaysOff() == null ? List.of() : req.regularDaysOff()));
        return new IdResponse(id);
    }

    /** 스탭 PIN 재설정 (FR-ACC-03). 원장 PIN. 원장 스탭의 PIN은 여기서 바꾸지 않는다 (FR-ACC-09) */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PutMapping("/api/staff/{staffId}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPin(@PathVariable long staffId, @Valid @RequestBody PinRequest req) {
        commandService.resetPin(shopSession.shopId(), staffId, req.pin());
    }

    /** 스탭 비활성화 (FR-ACC-04, F33). 원장 PIN. 남은 확정 예약이 있으면 conflict와 그 목록 */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PostMapping("/api/staff/{staffId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deactivate(@PathVariable long staffId) {
        commandService.deactivate(shopSession.shopId(), staffId);
    }

    /** 스탭 수정 (FR-ACC-03, FR-SET-07). 원장 PIN. 정기 휴무로 밀리는 확정 예약을 돌려주고 자동 취소하지 않는다 (F12) */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PatchMapping("/api/staff/{staffId}")
    PatchResponse update(@PathVariable long staffId, @Valid @RequestBody PatchRequest req) {
        return new PatchResponse(
                commandService
                        .update(
                                shopSession.shopId(),
                                staffId,
                                new StaffChanges(
                                        req.name(),
                                        req.lockedFeatureAllowed(),
                                        req.regularDaysOff()))
                        .stream()
                        .map(b ->
                                new PushedReservation(
                                        b.id(), TimeConfig.offset(b.startAt()), b.customerName()))
                        .toList());
    }

    record StaffItem(long id, String name) {}

    record StaffDetail(
            long id,
            String name,
            boolean owner,
            boolean lockedFeatureAllowed,
            List<DayOfWeek> regularDaysOff,
            StaffStatus status) {}

    /** 활성 스탭 목록, 담당 스탭 선택용 (FR-RSV-01, FR-ACC-04). 근무자 */
    @RequireWorker
    @GetMapping("/api/staff")
    List<StaffItem> activeStaff() {
        return queryService.activeStaff(shopSession.shopId()).stream()
                .map(s -> new StaffItem(s.getId(), s.getName()))
                .toList();
    }

    /** 스탭 관리 화면용, 비활성 포함 (FR-ACC-04, FR-SET-05). 원장 PIN */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @GetMapping(value = "/api/staff", params = "includeInactive=true")
    List<StaffDetail> allStaff() {
        return queryService.allStaff(shopSession.shopId()).stream()
                .map(s ->
                        new StaffDetail(
                                s.getId(),
                                s.getName(),
                                s.isOwner(),
                                s.isLockedFeatureAllowed(),
                                s.daysOff(),
                                s.getStatus()))
                .toList();
    }
}

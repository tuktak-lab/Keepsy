package com.tuktaklab.keepsy.settings;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.RequireWorker;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.settings.DayOffService.Created;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 휴무일 (CONTRACTS 4 · 6장 SET [제안]) */
@RestController
@RequiredArgsConstructor
class DayOffController {

    private final DayOffService dayOffService;
    private final ShopSession shopSession;

    record DayOffItem(long id, LocalDate date, Long staffId, String reason) {}

    record CreateRequest(
            @NotNull(message = "날짜를 고르세요") LocalDate date,
            Long staffId,
            @Size(max = 100, message = "사유는 100자 이하입니다") String reason) {}

    record PushedReservation(long id, OffsetDateTime startAt, String customerName) {}

    record CreateResponse(long id, List<PushedReservation> pushedReservations) {}

    /** 매장 · 스탭 임시 휴무일 (FR-SET-07). 근무자. {@code to} 포함, 최대 31일 (F22) */
    @RequireWorker
    @GetMapping("/api/day-offs")
    List<DayOffItem> dayOffs(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return dayOffService.dayOffs(shopSession.shopId(), from, to).stream()
                .map(d -> new DayOffItem(d.getId(), d.getOffDate(), d.getStaffId(), d.getReason()))
                .toList();
    }

    /** 휴무일 등록 (FR-SET-07, F32). 원장 PIN. 그날 걸리는 확정 예약을 돌려주고 자동 취소하지 않는다 (F12) */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PostMapping("/api/day-offs")
    @ResponseStatus(HttpStatus.CREATED)
    CreateResponse create(@Valid @RequestBody CreateRequest req) {
        Created created =
                dayOffService.create(shopSession.shopId(), req.date(), req.staffId(), req.reason());
        return new CreateResponse(
                created.id(),
                created.pushedReservations().stream()
                        .map(b ->
                                new PushedReservation(
                                        b.id(), TimeConfig.offset(b.startAt()), b.customerName()))
                        .toList());
    }

    /** 휴무일 삭제 (FR-SET-07). 원장 PIN. 그날 다시 예약할 수 있다 */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @DeleteMapping("/api/day-offs/{dayOffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long dayOffId) {
        dayOffService.delete(shopSession.shopId(), dayOffId);
    }
}

package com.tuktaklab.keepsy.settings;

import com.tuktaklab.keepsy.common.security.RequireWorker;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 휴무일 (CONTRACTS 4 · 6장 SET [제안]) */
@RestController
@RequiredArgsConstructor
class DayOffController {

    private final DayOffService dayOffService;
    private final ShopSession shopSession;

    record DayOffItem(long id, LocalDate date, Long staffId, String reason) {}

    /** 매장 · 스탭 임시 휴무일 (FR-SET-07). 근무자. {@code to} 포함, 최대 31일 (F22) */
    @RequireWorker
    @GetMapping("/api/day-offs")
    List<DayOffItem> dayOffs(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return dayOffService.dayOffs(shopSession.shopId(), from, to).stream()
                .map(d -> new DayOffItem(d.getId(), d.getOffDate(), d.getStaffId(), d.getReason()))
                .toList();
    }
}

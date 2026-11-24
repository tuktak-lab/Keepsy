package com.tuktaklab.keepsy.settings;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매장 · 스탭 임시 휴무일 (FR-SET-07). 기간은 최대 31일, 페이징 없음 (F22) */
@Service
@RequiredArgsConstructor
public class DayOffService {

    /** 조회 기간 상한(일), {@code to} 포함 (F22) */
    static final int MAX_DAYS = 31;

    private final DayOffRepository dayOffRepository;

    /**
     * @throws ApiException {@code from} > {@code to}이거나 31일을 넘으면 {@code invalid}
     */
    @Transactional(readOnly = true)
    public List<DayOff> dayOffs(long shopId, LocalDate from, LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ApiException(ErrorCode.INVALID, "기간은 31일 이내로 조회해 주세요");
        }
        return dayOffRepository.findByShopIdAndOffDateBetweenOrderByOffDateAscStaffIdAscIdAsc(
                shopId, from, to);
    }
}

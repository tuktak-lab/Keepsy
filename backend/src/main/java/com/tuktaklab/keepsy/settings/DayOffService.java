package com.tuktaklab.keepsy.settings;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler.FieldViolation;
import com.tuktaklab.keepsy.common.security.ShopScope;
import com.tuktaklab.keepsy.settings.port.DayOffReferences;
import com.tuktaklab.keepsy.settings.port.DayOffReferences.Booked;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final DayOffReferences references;
    private final Clock clock;

    /** 등록한 휴무일과 그날 걸리는 확정 예약 (F12 · F32) */
    public record Created(long id, List<Booked> pushedReservations) {}

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

    /**
     * 휴무일을 등록하고 그날(지금 이후) 걸리는 확정 예약을 돌려준다. 예약은 자동 취소하지 않는다 (FR-SET-07, C7, F12).
     *
     * @param staffId 매장 휴무면 null
     * @throws ApiException 지난 날짜 · 비활성 스탭이면 {@code invalid}, 다른 매장 · 없는 스탭이면 {@code not_found}, 같은
     *     날 같은 대상이 있으면 {@code duplicate} (F32)
     */
    @Transactional
    public Created create(long shopId, LocalDate date, Long staffId, String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (date.isBefore(now.toLocalDate())) {
            throw invalid("date", "지난 날짜는 휴무로 등록할 수 없습니다");
        }
        if (staffId != null) {
            boolean active = ShopScope.orNotFound(references.staffActive(shopId, staffId));
            if (!active) {
                throw invalid("staffId", "비활성 스탭은 휴무를 등록할 수 없습니다");
            }
        }
        // ponytail: 테이블에 고유 제약이 없어(staff_id NULL) 같은 요청이 동시에 오면 둘 다 들어갈 수 있다. 원장 한 명이 쓰는 화면이라
        // 미루고, 문제가 되면 매장 행 잠금(F14 방식)을 건다
        boolean exists =
                staffId == null
                        ? dayOffRepository.existsByShopIdAndOffDateAndStaffIdIsNull(shopId, date)
                        : dayOffRepository.existsByShopIdAndOffDateAndStaffId(
                                shopId, date, staffId);
        if (exists) {
            throw new ApiException(ErrorCode.DUPLICATE, "그날 이미 같은 휴무가 있습니다");
        }
        long id = dayOffRepository.save(DayOff.create(shopId, staffId, date, reason)).getId();
        return new Created(id, references.confirmedOn(shopId, staffId, date, now));
    }

    /**
     * @throws ApiException 다른 매장 · 없는 휴무일이면 {@code not_found}
     */
    @Transactional
    public void delete(long shopId, long dayOffId) {
        dayOffRepository.delete(
                ShopScope.orNotFound(dayOffRepository.findByIdAndShopId(dayOffId, shopId)));
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(
                ErrorCode.INVALID, reason, List.of(new FieldViolation(field, reason)));
    }
}

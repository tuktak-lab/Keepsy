package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.Shop.OpeningHours;
import com.tuktaklab.keepsy.account.ShopInfoService.ShopInfo;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.RequireWorker;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 매장 가입(비로그인) · 매장 정보 조회(근무자) · 수정(원장 PIN) API (CONTRACTS 4 · 6장 [제안]) */
@RestController
@RequiredArgsConstructor
class ShopController {

    /** 날짜 없는 시각 (CONTRACTS 3장 「시:분」, F20) */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ShopInfoService infoService;
    private final ShopSession shopSession;

    record OpeningHoursResponse(DayOfWeek dayOfWeek, String open, String close) {}

    record ShopResponse(
            String name,
            String phone,
            String senderNumber,
            String unsubscribeNumber,
            List<OpeningHoursResponse> openingHours) {}

    /** 매장 정보 · 영업시간 (FR-SET-01). 근무자. 매장 설정 화면과 예약 화면이 함께 쓴다 (F20) */
    @RequireWorker
    @GetMapping("/api/shop")
    ShopResponse shop() {
        ShopInfo info = infoService.get(shopSession.shopId());
        return new ShopResponse(
                info.name(),
                info.phone(),
                info.senderNumber(),
                info.unsubscribeNumber(),
                info.openingHours().stream()
                        .map(h ->
                                new OpeningHoursResponse(
                                        h.dayOfWeek(), hhmm(h.open()), hhmm(h.close())))
                        .toList());
    }

    private static String hhmm(LocalTime time) {
        return time == null ? null : time.format(HH_MM);
    }

    /** 국내 전화번호: 0으로 시작하는 번호, 15xx · 16xx · 18xx 대표번호. 하이픈 선택. 빈 값은 null로 저장 (F39, NFR-08) */
    private static final String SHOP_NUMBER = "|0\\d{1,2}-?\\d{3,4}-?\\d{4}|1[568]\\d{2}-?\\d{4}";

    private static final String SHOP_NUMBER_MESSAGE = "전화번호 형식이 아닙니다 (예: 02-000-0000, 1588-0000)";

    record OpeningHoursRequest(
            @NotNull(message = "요일을 보내 주세요") DayOfWeek dayOfWeek,
            LocalTime open,
            LocalTime close) {}

    record UpdateRequest(
            @NotBlank(message = "매장명을 입력해 주세요") @Size(max = 100, message = "매장명은 100자 이하입니다")
                    String name,
            @Pattern(regexp = SHOP_NUMBER, message = SHOP_NUMBER_MESSAGE) String phone,
            @Pattern(regexp = SHOP_NUMBER, message = SHOP_NUMBER_MESSAGE) String senderNumber,
            @Pattern(regexp = SHOP_NUMBER, message = SHOP_NUMBER_MESSAGE) String unsubscribeNumber,
            @NotNull(message = "요일별 영업시간을 보내 주세요")
                    List<@NotNull @Valid OpeningHoursRequest> openingHours) {}

    record PushedReservation(long id, OffsetDateTime startAt, String customerName) {}

    record UpdateResponse(List<PushedReservation> pushedReservations) {}

    /** 매장 정보 · 영업시간 수정 (FR-SET-01 · 07, F39). 원장 PIN. 밀린 확정 예약을 돌려주고 자동 취소하지 않는다 (F12) */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PutMapping("/api/shop")
    UpdateResponse update(@Valid @RequestBody UpdateRequest req) {
        ShopInfo in =
                new ShopInfo(
                        req.name(),
                        req.phone(),
                        req.senderNumber(),
                        req.unsubscribeNumber(),
                        req.openingHours().stream()
                                .map(h -> new OpeningHours(h.dayOfWeek(), h.open(), h.close()))
                                .toList());
        return new UpdateResponse(
                infoService.update(shopSession.shopId(), in).stream()
                        .map(r ->
                                new PushedReservation(
                                        r.id(), TimeConfig.offset(r.startAt()), r.customerName()))
                        .toList());
    }

    private final ShopSignupService signupService;

    record SignUpRequest(
            @NotBlank(message = "이메일을 입력해 주세요")
                    @Email(message = "이메일 형식이 아닙니다")
                    @Size(max = 255, message = "이메일은 255자 이하입니다")
                    String email,
            @NotNull(message = "비밀번호를 입력해 주세요") @Size(min = 8, max = 64, message = "비밀번호는 8~64자입니다")
                    String password,
            @NotBlank(message = "매장명을 입력해 주세요") @Size(max = 100, message = "매장명은 100자 이하입니다")
                    String shopName,
            @NotBlank(message = "원장 이름을 입력해 주세요") @Size(max = 50, message = "원장 이름은 50자 이하입니다")
                    String ownerName,
            @NotNull(message = "원장 PIN을 입력해 주세요")
                    @Pattern(regexp = "\\d{4,6}", message = "PIN은 숫자 4~6자리입니다")
                    String ownerPin) {}

    record SignUpResponse(long shopId) {}

    @PostMapping("/api/shops")
    @ResponseStatus(HttpStatus.CREATED)
    SignUpResponse signUp(@Valid @RequestBody SignUpRequest request) {
        long shopId =
                signupService.signUp(
                        request.email(),
                        request.password(),
                        request.shopName(),
                        request.ownerName(),
                        request.ownerPin());
        return new SignUpResponse(shopId);
    }
}

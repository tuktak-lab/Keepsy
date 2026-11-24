package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.query.CampaignTargetQuery;
import com.tuktaklab.keepsy.campaign.query.CampaignTargetQuery.Candidate;
import com.tuktaklab.keepsy.campaign.query.CampaignTargetQuery.Selection;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캠페인 대상 선정 · 자동 제외 (FR-CMP-01 · 02). 대상 미리 계산과 캠페인 만들기(#89)가 같은 규칙을 쓰고, 예약 발송 재검사(#97)도 같은 판정
 * ({@link ExclusionReason#of})을 쓴다.
 */
@Service
@RequiredArgsConstructor
public class CampaignTargets {

    /** 발송 대상 고객 */
    public record Target(long customerId, String phone) {}

    /**
     * @param targets 제외 후 발송 대상 (대조군 분리 전), 고객 ID 순
     * @param excludedCounts 제외 사유마다 고객 수, 0건 포함 (고객당 사유 하나)
     */
    public record Result(List<Target> targets, Map<ExclusionReason, Integer> excludedCounts) {}

    private final CampaignTargetQuery query;
    private final Clock clock;

    /**
     * @throws ApiException 고르기 조건이 맞지 않으면 {@code invalid}: 광고인데 카테고리 없음 · 동의 재확인 안내인데 카테고리나 조건이 있거나
     *     고객 목록이 없음 · 범위가 거꾸로임 · 그 밖의 메시지 종류
     */
    @Transactional(readOnly = true)
    public Result select(long shopId, Selection s) {
        validate(s);
        LocalDateTime now = LocalDateTime.now(clock);
        int resendLimitDays = query.resendLimitDays(shopId);
        List<Target> targets = new ArrayList<>();
        Map<ExclusionReason, Integer> excluded = new EnumMap<>(ExclusionReason.class);
        for (ExclusionReason r : ExclusionReason.values()) {
            excluded.put(r, 0);
        }
        for (Candidate c : query.find(shopId, s, now.toLocalDate())) {
            Optional<ExclusionReason> reason =
                    ExclusionReason.of(
                            s.kind(),
                            c.marketingConsent(),
                            c.unsubscribed(),
                            Optional.ofNullable(c.lastAdSentAt()),
                            resendLimitDays,
                            now);
            if (reason.isPresent()) {
                excluded.merge(reason.get(), 1, Integer::sum);
            } else {
                targets.add(new Target(c.customerId(), c.phone()));
            }
        }
        return new Result(targets, excluded);
    }

    private static void validate(Selection s) {
        if (s.kind() == MessageKind.AD) {
            if (s.category() == null) {
                throw new ApiException(ErrorCode.INVALID, "시술 카테고리를 골라 주세요");
            }
        } else if (s.kind() == MessageKind.CONSENT_RECONFIRM) {
            // 동의 재확인 안내는 카테고리가 없고(E7) 재확인 대상 목록(FR-CMP-13)에서 고른 고객에게만 보낸다
            if (s.category() != null
                    || s.predictionStatus() != null
                    || s.lastServiceFrom() != null
                    || s.lastServiceTo() != null
                    || s.visitCountMin() != null
                    || s.visitCountMax() != null) {
                throw new ApiException(ErrorCode.INVALID, "동의 재확인 안내는 카테고리 · 조건 없이 고객을 골라 보냅니다");
            }
            if (s.customerIds() == null || s.customerIds().isEmpty()) {
                throw new ApiException(ErrorCode.INVALID, "동의 재확인 안내를 보낼 고객을 골라 주세요");
            }
        } else {
            throw new ApiException(ErrorCode.INVALID, "캠페인 메시지 종류가 아닙니다");
        }
        if (s.visitCountMin() != null
                && s.visitCountMax() != null
                && s.visitCountMin() > s.visitCountMax()) {
            throw new ApiException(ErrorCode.INVALID, "방문 횟수 범위를 확인해 주세요");
        }
        if (s.lastServiceFrom() != null
                && s.lastServiceTo() != null
                && s.lastServiceFrom().isAfter(s.lastServiceTo())) {
            throw new ApiException(ErrorCode.INVALID, "마지막 시술일 범위를 확인해 주세요");
        }
    }
}

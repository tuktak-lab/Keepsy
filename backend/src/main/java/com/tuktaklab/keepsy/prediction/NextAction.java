package com.tuktaklab.keepsy.prediction;

import java.util.Optional;

/**
 * 다음 액션 제안 (FR-PRD-06, 기획서 9장, 결정체크리스트 F19). 값 이름은 CONTRACTS 3장 「다음 액션」을 따른다. 앞의 세 값은 템플릿 용도와 같은
 * 이름이라 응답에 템플릿 ID 대신 이 값을 내려준다.
 */
public enum NextAction {
    /** 재방문 안내 템플릿 (주기 도래 + 동의) */
    REVISIT,
    /** 안부 · 혜택 템플릿 (이탈 신호 + 동의) */
    CHECK_IN,
    /** 재방문 혜택 템플릿 (장기 미방문 + 동의) */
    WIN_BACK,
    /** 다음 방문 시 동의 요청 (동의를 받은 적 없음) */
    REQUEST_CONSENT;

    /**
     * @param status 예측 상태 ({@link PredictionStatusClassifier#classify})
     * @param marketingConsent 마케팅 동의 이력의 최신 행이 동의인지
     * @param marketingWithdrawn 최신 행이 철회(철회 경로 {@code DIRECT} · {@code UNSUBSCRIBE})인지. 철회 경로 없는
     *     미동의 행(CSV 가져오기 포함)이나 행이 없으면 false
     * @return 제안. 예정 · 예측 없음, 동의를 철회한 고객이면 비어 있다 (F19)
     */
    public static Optional<NextAction> suggest(
            PredictionStatus status, boolean marketingConsent, boolean marketingWithdrawn) {
        NextAction byStatus =
                switch (status) {
                    case DUE -> REVISIT;
                    case CHURN_SIGNAL -> CHECK_IN;
                    case LONG_ABSENT -> WIN_BACK;
                    case SCHEDULED, NO_PREDICTION -> null;
                };
        if (byStatus == null || (!marketingConsent && marketingWithdrawn)) {
            return Optional.empty();
        }
        return Optional.of(marketingConsent ? byStatus : REQUEST_CONSENT);
    }
}

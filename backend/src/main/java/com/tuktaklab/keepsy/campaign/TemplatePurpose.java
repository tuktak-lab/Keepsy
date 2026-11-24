package com.tuktaklab.keepsy.campaign;

import java.util.Set;

/** 템플릿 용도 (FR-CMP-03, CONTRACTS 3장 「템플릿 용도」). 캠페인 메시지 종류는 용도로 정해진다 */
public enum TemplatePurpose {
    REVISIT,
    CHECK_IN,
    WIN_BACK,
    CONSENT_RECONFIRM,
    ETC;

    /** 캠페인 메시지 종류 (CONTRACTS 3장): 동의 재확인만 안내, 나머지(기타 포함)는 광고 (FR-CMP-03) */
    public enum MessageKind {
        AD,
        INFO
    }

    public MessageKind messageKind() {
        return this == CONSENT_RECONFIRM ? MessageKind.INFO : MessageKind.AD;
    }

    /**
     * 이 용도에서 쓸 수 있는 변수 (C10, FR-CMP-03). 동의 재확인만 {동의일} {야간광고동의}를 쓰고, 카테고리가 없어 {최근시술} {경과일}은 못 쓴다
     */
    public Set<String> allowedVariables() {
        return this == CONSENT_RECONFIRM
                ? Set.of("고객명", "매장명", "동의일", "야간광고동의")
                : Set.of("고객명", "매장명", "최근시술", "경과일");
    }
}

package com.tuktaklab.keepsy.campaign;

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
}

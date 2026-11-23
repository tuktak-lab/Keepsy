package com.tuktaklab.keepsy.campaign;

/** 발송 메시지 종류 (명세서 5장 campaign_message). 값 이름은 CONTRACTS 3장 [제안]을 따른다. */
public enum MessageKind {
    /** 광고. 광고 표기를 붙인다 (FR-CMP-04) */
    AD,
    /** 수신 거부 처리 안내 (FR-CMP-11). 광고 표기를 붙이지 않는다 */
    UNSUBSCRIBE_NOTICE,
    /** 동의 재확인 안내 (FR-CMP-13). 광고 표기를 붙이지 않는다 */
    CONSENT_RECONFIRM
}

package com.tuktaklab.keepsy.campaign;

/** 발송 상태 (FR-CMP-10). 대조군 행은 발송하지 않으므로 상태가 없다. 값 이름은 CONTRACTS 3장 [제안]을 따른다. */
public enum SendStatus {
    PENDING,
    SENT,
    FAILED
}

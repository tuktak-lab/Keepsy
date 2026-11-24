package com.tuktaklab.keepsy.common.error;

import org.springframework.http.HttpStatus;

/**
 * 오류 code와 HTTP 상태 (docs/CONTRACTS.md 2장 [제안]). 화면은 {@link #code()}로 분기한다. 새 code는 CONTRACTS 2장 표에
 * 먼저 추가한다.
 */
public enum ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다"),
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 많아 잠시 잠겼습니다. 잠시 후 다시 시도해 주세요"),
    WORKER_REQUIRED(HttpStatus.FORBIDDEN, "근무자 PIN을 먼저 입력해 주세요"),
    PIN_INVALID(HttpStatus.BAD_REQUEST, "PIN이 맞지 않습니다"),
    PIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "PIN 입력 시도가 많아 잠시 막혔습니다. 잠시 후 다시 시도해 주세요"),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호가 맞지 않습니다"),
    PIN_REQUIRED(HttpStatus.FORBIDDEN, "PIN을 입력해 주세요"),
    OWNER_PIN_REQUIRED(HttpStatus.FORBIDDEN, "원장 PIN이 필요합니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다"),
    INVALID(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "요청이 만료되었습니다. 새로고침 후 다시 시도해 주세요"),
    DUPLICATE(HttpStatus.CONFLICT, "이미 등록되어 있습니다"),
    RESERVATION_CONFLICT(HttpStatus.CONFLICT, "선택한 시간에는 예약할 수 없습니다"),
    CONSENT_REQUIRED(HttpStatus.CONFLICT, "동의가 필요합니다"),
    REVIEW_REQUIRED(HttpStatus.CONFLICT, "대상과 문구를 검토했다는 체크가 필요합니다"),
    NIGHT_SEND_BLOCKED(HttpStatus.CONFLICT, "야간(21시~8시)에는 즉시 발송할 수 없습니다"),
    DAILY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "오늘 발송 한도를 넘었습니다"),
    CONFLICT(HttpStatus.CONFLICT, "지금 상태에서는 처리할 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    /** 응답의 {@code code}. 소문자 snake (예: {@code pin_required}) */
    public String code() {
        return name().toLowerCase();
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}

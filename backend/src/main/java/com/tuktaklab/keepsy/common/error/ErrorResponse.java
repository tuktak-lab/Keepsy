package com.tuktaklab.keepsy.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 오류 응답 모양 (CONTRACTS 2장): {@code { "error": { "code": "pin_required", "message": "PIN을 입력해 주세요" }
 * }}. {@code details}는 값이 있을 때만 나간다.
 */
public record ErrorResponse(Body error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String code, String message, Object details) {}

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.defaultMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, Object details) {
        return new ErrorResponse(new Body(errorCode.code(), message, details));
    }
}

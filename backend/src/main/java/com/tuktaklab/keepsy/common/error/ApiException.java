package com.tuktaklab.keepsy.common.error;

/**
 * 업무 규칙 위반을 알리는 공통 예외 하나 (DEVELOPMENT 4장 「구조」). 전역 예외 처리가 CONTRACTS 2장 모양으로 바꾼다.
 *
 * <pre>{@code
 * throw new ApiException(ErrorCode.NOT_FOUND);
 * throw new ApiException(ErrorCode.OWNER_PIN_REQUIRED, "예약 제한 고객입니다. 사유: " + reason);
 * throw new ApiException(ErrorCode.CONFLICT, "남은 예약이 있습니다", reservations);
 * }</pre>
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object details;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * @param details 화면이 더 보여줄 값. 필요할 때만 (예: 스탭 비활성화를 막는 예약 목록, FR-ACC-04)
     */
    public ApiException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object details() {
        return details;
    }
}

package com.tuktaklab.keepsy.common.error;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 API 오류를 CONTRACTS 2장 모양으로 바꾸는 한 곳 (DEVELOPMENT 4장). Spring MVC가 던지는 표준 예외(본문 형식 오류, 없는 경로,
 * 지원하지 않는 메서드 등)는 상위 클래스가 받고 {@link #handleExceptionInternal}에서 모양만 바꾼다. Spring Security의 인증 · 인가
 * 실패는 필터에서 나므로 보안 설정이 {@link ErrorResponse}로 직접 응답한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Bean Validation 실패 한 칸 */
    public record FieldViolation(String field, String reason) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        ErrorCode code = e.errorCode();
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, e.getMessage(), e.details()));
    }

    /** 처리하지 못한 예외. 내부 정보는 응답에 넣지 않고 로그에 한 번만 남긴다 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리하지 못한 예외", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    /** {@code @Valid} 요청 본문 검증 실패 → {@code invalid} 400, 칸별 사유는 details (NFR-08) */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldViolation> violations =
                e.getBindingResult().getFieldErrors().stream()
                        .map(f -> new FieldViolation(f.getField(), f.getDefaultMessage()))
                        .toList();
        return ResponseEntity.status(ErrorCode.INVALID.status())
                .body(
                        ErrorResponse.of(
                                ErrorCode.INVALID, ErrorCode.INVALID.defaultMessage(), violations));
    }

    /** 그 밖의 Spring MVC 표준 예외: HTTP 상태는 그대로 두고 404는 not_found, 5xx는 internal_error, 나머지는 invalid */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ErrorCode code;
        if (status.value() == 404) {
            code = ErrorCode.NOT_FOUND;
        } else if (status.is5xxServerError()) {
            log.error("처리하지 못한 예외", e);
            code = ErrorCode.INTERNAL_ERROR;
        } else {
            code = ErrorCode.INVALID;
        }
        return ResponseEntity.status(status).headers(headers).body(ErrorResponse.of(code));
    }
}

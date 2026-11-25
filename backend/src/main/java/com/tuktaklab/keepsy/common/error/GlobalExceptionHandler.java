package com.tuktaklab.keepsy.common.error;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    /**
     * DB 잠금 대기 시간 초과 · 교착 (MySQL 1205 · 1213) → {@code conflict} 409. 서버는 다시 시도하지 않고 사용자에게 맡긴다
     * (F63, CONTRACTS 2장)
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> handleLockConflict(PessimisticLockingFailureException e) {
        log.warn("DB 잠금 충돌", SqlSafeException.of(e));
        return ResponseEntity.status(ErrorCode.CONFLICT.status())
                .body(ErrorResponse.of(ErrorCode.CONFLICT, LOCK_CONFLICT_MESSAGE, null));
    }

    static final String LOCK_CONFLICT_MESSAGE = "다른 작업과 겹쳤습니다. 잠시 후 다시 시도해 주세요";

    /**
     * 처리하지 못한 예외. 내부 정보는 응답에 넣지 않고 로그에 한 번만 남긴다. DB 예외는 메시지에 SQL 값 · 연락처가 들어가므로 종류 · SQL 상태 · 오류 번호
     * · 제약 이름 · 스택만 남긴다 (F63, NFR-06)
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리하지 못한 예외", SqlSafeException.isSql(e) ? SqlSafeException.of(e) : e);
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

    /**
     * 업로드 크기 한도(`spring.servlet.multipart`) 초과. Spring 기본은 413이지만 파일 크기는 입력 오류라 CONTRACTS 2장대로
     * {@code invalid} 400으로 맞춘다 (FR-CUS-08, C4)
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID, "파일이 너무 큽니다", null));
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

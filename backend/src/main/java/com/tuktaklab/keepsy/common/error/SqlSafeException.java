package com.tuktaklab.keepsy.common.error;

import java.sql.SQLException;
import org.hibernate.JDBCException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;

/**
 * 로그용으로 DB 예외의 메시지를 뺀 사본 (F63, NFR-06). MySQL 메시지(예: {@code Duplicate entry '1-010-…'})에는 SQL 값 ·
 * 연락처가 들어가므로, 원인 사슬의 각 예외를 「클래스 이름 + SQL 상태 · 오류 번호 · 제약 이름」만 담은 이 예외로 바꾸고 스택은 그대로 옮긴다.
 */
final class SqlSafeException extends RuntimeException {

    private SqlSafeException(String summary, Throwable cause, StackTraceElement[] stack) {
        super(summary, cause, false, true);
        setStackTrace(stack);
    }

    /** 원인 사슬에 DB 예외가 있으면 true */
    static boolean isSql(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException
                    || t instanceof DataAccessException
                    || t instanceof JDBCException) {
                return true;
            }
        }
        return false;
    }

    static SqlSafeException of(Throwable e) {
        Throwable cause = e.getCause();
        return new SqlSafeException(
                summary(e), cause == null || cause == e ? null : of(cause), e.getStackTrace());
    }

    private static String summary(Throwable t) {
        StringBuilder s = new StringBuilder(t.getClass().getName());
        if (t instanceof SQLException sql) {
            s.append(" sqlState=").append(sql.getSQLState());
            s.append(" errorCode=").append(sql.getErrorCode());
        }
        if (t instanceof ConstraintViolationException cv && cv.getConstraintName() != null) {
            s.append(" constraint=").append(cv.getConstraintName());
        }
        return s.toString();
    }
}

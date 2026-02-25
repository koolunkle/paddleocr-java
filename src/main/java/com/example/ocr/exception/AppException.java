package com.example.ocr.exception;

import org.springframework.http.HttpStatus;

/**
 * 애플리케이션 내에서 발생하는 모든 커스텀 예외의 최상위 부모(Base) 클래스입니다.
 * 예외 발생 시 클라이언트에게 반환할 HTTP 응답 상태 코드(HttpStatus)를 함께 관리합니다.
 */
public abstract class AppException extends RuntimeException {

    // ========================================================================
    // 상태 변수
    // ========================================================================

    /** API 응답 시 사용할 HTTP 상태 코드 */
    private final HttpStatus httpStatus;

    // ========================================================================
    // 생성자 
    // ========================================================================

    /**
     * 에러 메시지와 HTTP 상태 코드를 지정하여 예외를 생성합니다.
     *
     * @param message    클라이언트나 로그에 남길 에러 상세 메시지
     * @param httpStatus API 응답으로 반환할 HTTP 상태 코드
     */
    protected AppException(String message, HttpStatus httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    /**
     * 에러 메시지, HTTP 상태 코드와 함께 원인(Cause)이 되는 예외를 포함하여 생성합니다.
     * (Exception Chaining을 통해 기존 에러의 스택 트레이스를 유실 없이 보존할 때 사용)
     *
     * @param message    클라이언트나 로그에 남길 에러 상세 메시지
     * @param httpStatus API 응답으로 반환할 HTTP 상태 코드
     * @param cause      원인이 되는 이전 예외 객체
     */
    protected AppException(String message, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    // ========================================================================
    // 공개 메서드
    // ========================================================================

    /**
     * 이 예외에 매핑된 HTTP 상태 코드를 반환합니다.
     * GlobalExceptionHandler 등에서 최종 API 응답 코드를 결정할 때 사용됩니다.
     */
    public HttpStatus getHttpStatus() {
        return this.httpStatus;
    }
}
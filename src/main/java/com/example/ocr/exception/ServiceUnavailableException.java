package com.example.ocr.exception;

import org.springframework.http.HttpStatus;

/**
 * 서버 리소스 제한(예: OCR 추론 엔진의 스레드 풀 고갈, 세마포어 대기 시간 초과)으로 인해 
 * 현재 요청을 처리할 수 없을 때 발생하는 예외 클래스입니다.
 * (기본 API 응답 상태 코드: 503 SERVICE_UNAVAILABLE)
 */
public class ServiceUnavailableException extends AppException {

    // ========================================================================
    // 상수 
    // ========================================================================

    /** 503 에러 발생 시 클라이언트에게 내려보낼 기본 메시지 */
    private static final String DEFAULT_ERROR_MESSAGE = 
            "The service is temporarily unavailable due to resource constraints. Please try again shortly.";

    // ========================================================================
    // 생성자 
    // ========================================================================

    /**
     * 기본 에러 메시지와 함께 예외를 생성합니다. 
     */
    public ServiceUnavailableException() {
        super(DEFAULT_ERROR_MESSAGE, HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * 상황에 맞는 구체적인 커스텀 에러 메시지를 지정하여 예외를 생성합니다.
     * (예: "OCR Inference Engine is busy")
     *
     * @param message 클라이언트나 로그에 남길 에러 상세 메시지
     */
    public ServiceUnavailableException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * 커스텀 에러 메시지와 함께 원인(Cause)이 되는 이전 예외를 포함하여 생성합니다.
     * (Exception Chaining을 통해 기존 예외의 스택 트레이스를 보존)
     *
     * @param message 클라이언트나 로그에 남길 에러 상세 메시지
     * @param cause   원인이 되는 이전 예외 객체
     */
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, cause);
    }
}
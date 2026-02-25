package com.example.ocr.exception;

import org.springframework.http.HttpStatus;

/**
 * OCR 문서 파싱, 이미지 분석 등 파이프라인 수행 중 발생하는 
 * 일반적인 런타임 오류를 표현하는 예외 클래스입니다.
 * (기본 API 응답 상태 코드: 500 INTERNAL_SERVER_ERROR)
 */
public class ProcessingException extends AppException {

    // ========================================================================
    // 생성자 
    // ========================================================================

    /**
     * 에러 메시지를 지정하여 예외를 생성합니다.
     *
     * @param message 클라이언트나 로그에 남길 에러 상세 메시지
     */
    public ProcessingException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 에러 메시지와 함께 원인(Cause)이 되는 이전 예외를 포함하여 생성합니다.
     * (Exception Chaining을 통해 기존 예외의 스택 트레이스를 보존)
     *
     * @param message 클라이언트나 로그에 남길 에러 상세 메시지
     * @param cause   원인이 되는 이전 예외 객체
     */
    public ProcessingException(String message, Throwable cause) {
        // initCause()를 따로 호출할 필요 없이, 
        // 부모 클래스(AppException)의 생성자를 통해 한 번에 처리합니다.
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }
}
package com.example.ocr.exception;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.example.ocr.constant.AppConstants;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 애플리케이션 전역에서 발생하는 예외를 가로채어
 * 클라이언트에게 일관된 규격의 에러 응답(ErrorResponse)을 반환하는 통합 핸들러
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ========================================================================
    // 로거 
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ========================================================================
    // 내부 데이터 구조
    // ========================================================================

    /**
     * API 에러 발생 시 클라이언트에게 반환될 표준 응답 객체 포맷
     */
    public record ErrorResponse(
            LocalDateTime timestamp, 
            int status, 
            String error, 
            String message, 
            String path
    ) {}

    // ========================================================================
    // 예외 처리기 
    // ========================================================================

    /**
     * 비즈니스 로직 수행 중 의도적으로 발생시킨 커스텀 예외 처리
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleApplicationException(AppException exception, HttpServletRequest request) {
        log.warn("[GlobalExceptionHandler] Business Exception - Status: {}, Message: {}", exception.getHttpStatus(), exception.getMessage());
        return buildErrorResponse(exception.getHttpStatus(), exception.getMessage(), request);
    }

    /**
     * 파일 업로드 용량 제한을 초과했을 때 발생하는 예외 처리
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        log.warn("[GlobalExceptionHandler] Upload size exceeded: {}", exception.getMessage());
        return buildErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds the maximum allowed size", request);
    }

    /**
     * 클라이언트의 잘못된 요청(파라미터 누락, 타입 불일치 등)으로 인한 예외 처리
     */
    @ExceptionHandler({
        IllegalArgumentException.class, 
        MissingServletRequestParameterException.class, 
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequestExceptions(Exception exception, HttpServletRequest request) {
        log.warn("[GlobalExceptionHandler] Bad Request - URI: {}, Message: {}", request.getRequestURI(), exception.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    /**
     * 파일 처리나 네트워크 통신 중 발생하는 I/O 예외 처리
     * (클라이언트가 의도적으로 연결을 끊은 경우는 불필요한 서버 에러 로그를 남기지 않음)
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIOException(IOException exception, HttpServletRequest request) {
        if (isClientDisconnected(exception)) {
            log.debug("[GlobalExceptionHandler] Client disconnected during I/O operation (URI: {})", request.getRequestURI());
            return null; // 이미 연결 소켓이 닫혔으므로 응답 본문을 보내지 않음
        }
        
        log.error("[GlobalExceptionHandler] I/O Operation failed: {}", exception.getMessage(), exception);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Server I/O processing failed", request);
    }

    /**
     * 위에서 처리되지 않은 모든 예상치 못한 서버 내부 예외 처리 (Catch-All)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        log.error("[GlobalExceptionHandler] Unexpected Internal Server Error (URI: {})", request.getRequestURI(), exception);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal server error occurred", request);
    }

    // ========================================================================
    // 비공개 헬퍼 메서드
    // ========================================================================

    /**
     * 공통 규격의 ErrorResponse 객체를 생성하여 ResponseEntity로 감싸 반환합니다.
     */
    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus httpStatus, String errorMessage, HttpServletRequest request) {
        Objects.requireNonNull(httpStatus, "HttpStatus must not be null");
        
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(), 
                httpStatus.value(), 
                httpStatus.getReasonPhrase(), 
                errorMessage, 
                request.getRequestURI()
        );
        
        return ResponseEntity.status(httpStatus).body(errorResponse);
    }

    /**
     * IOException 발생 시, 클라이언트의 자발적 연결 끊김(Broken pipe 등)인지 확인합니다.
     */
    private boolean isClientDisconnected(Exception exception) {
        String exceptionMessage = exception.getMessage();
        
        if (exceptionMessage == null || exceptionMessage.isBlank()) {
            return false;
        }
        
        return AppConstants.Policy.DISCONNECTS.stream()
                .anyMatch(exceptionMessage::contains);
    }
}
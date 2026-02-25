package com.example.ocr.support;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.ocr.constant.AppConstants;

/**
 * SSE(Server-Sent Events) 세션을 안전하게 관리하고 통신을 담당하는 래퍼(Wrapper) 클래스입니다.
 * 멀티스레드 환경에서의 동시성 문제와 클라이언트의 비정상 종료(Broken pipe 등)를 방어합니다.
 */
public class SseSessionWrapper {

    // ========================================================================
    // 상수 및 로거
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(SseSessionWrapper.class);

    // ========================================================================
    // 상태 변수 
    // ========================================================================

    private final SseEmitter emitter;
    private final String sessionId;
    
    /** * 세션의 활성화 상태를 스레드 안전(Thread-safe)하게 관리합니다.
     * 여러 스레드에서 동시에 접근하여 전송/종료를 시도할 때 발생할 수 있는 충돌을 방지합니다. 
     */
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // ========================================================================
    // 생성자
    // ========================================================================

    /**
     * SSE 세션을 초기화하고 생명주기(Lifecycle) 콜백을 등록합니다.
     *
     * @param timeout   SSE 연결 유지 시간 (밀리초 단위)
     * @param sessionId 클라이언트 또는 작업(Job)을 식별하는 고유 ID
     */
    public SseSessionWrapper(Long timeout, String sessionId) {
        this.emitter = new SseEmitter(Objects.requireNonNull(timeout, "Timeout must not be null"));
        this.sessionId = Objects.requireNonNullElse(sessionId, AppConstants.UNKNOWN);

        // SSE Emitter 생명주기 이벤트 등록 (종료 시 내부 상태를 비활성화)
        this.emitter.onCompletion(() -> this.isActive.set(false));
        this.emitter.onTimeout(this::close);
        
        // 에러 발생 시 자동 닫기 (메모리 릭 방지)
        this.emitter.onError(error -> close());
    }

    // ========================================================================
    // 공개 메서드 
    // ========================================================================

    /**
     * Spring MVC 컨트롤러에 반환할 원본 SseEmitter 객체를 가져옵니다.
     */
    public SseEmitter getEmitter() { 
        return this.emitter; 
    }

    /**
     * 클라이언트에게 이벤트를 안전하게 전송합니다.
     *
     * @param eventName 전송할 이벤트의 이름 (프론트엔드에서 수신할 채널명)
     * @param data      전송할 데이터 페이로드 (Jackson에 의해 JSON으로 자동 직렬화됨)
     */
    public void send(String eventName, Object data) {
        if (eventName == null || data == null || !this.isActive.get()) {
            return;
        }

        try {
            this.emitter.send(
                    SseEmitter.event()
                            .name(eventName)
                            .data(data, MediaType.APPLICATION_JSON)
            );
            
        } catch (IOException e) {
            // 클라이언트가 브라우저를 닫는 등의 이유로 연결이 끊어진 경우
            if (isClientDisconnected(e)) {
                log.debug("[SseUtil] Client normally disconnected. Session ID: {}", this.sessionId);
            } else {
                log.error("[SseUtil] Failed to send event to Session ID: {}", this.sessionId, e);
            }
            // 연결에 문제가 생겼으므로 즉시 자원 해제
            close();
        }
    }

    /**
     * 더 이상 보낼 이벤트가 없거나, 타임아웃/에러가 발생했을 때 
     * SSE 연결을 정상적으로 종료하고 자원을 해제합니다.
     */
    public void close() {
        // 이미 닫혔거나 다른 스레드가 닫는 중이라면 무시
        if (this.isActive.compareAndSet(true, false)) {
            try { 
                this.emitter.complete(); 
            } catch (Exception ignored) {
                // 이미 종료되었거나 네트워크가 끊어진 상태이므로 무시
            }
        }
    }

    /**
     * 서버 측 비즈니스 로직(파이프라인 등) 수행 중 치명적인 예외가 발생했을 때 
     * 에러 신호와 함께 연결을 강제로 종료합니다.
     *
     * @param error 발생한 원인 예외 객체
     */
    public void closeWithError(Throwable error) {
        if (!this.isActive.compareAndSet(true, false)) {
            return;
        }

        try {
            // 네트워크 단절로 인한 에러라면 정상 종료(complete)로 덮어씌움
            if (isClientDisconnected(error)) {
                this.emitter.complete();
                return; 
            }
            
            this.emitter.completeWithError(Objects.requireNonNull(error));
            
        } catch (Exception ignored) {
            // 종료 과정 중 클라이언트 소켓이 닫혀 발생하는 부가적인 예외는 무시
        }
    }

    // ========================================================================
    // 비공개 유틸리티 메서드 
    // ========================================================================

    /**
     * 발생한 예외의 메시지를 분석하여, 클라이언트가 자발적 혹은 네트워크 이슈로 
     * 연결 소켓(Pipe)을 끊은 것인지 확인합니다.
     * (이 경우 불필요한 Error 레벨 로깅을 방지합니다.)
     *
     * @param error 분석 대상이 되는 예외 객체
     * @return 클라이언트 연결 해제에 의한 에러라면 true, 그 외 서버 에러라면 false
     */
    private boolean isClientDisconnected(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return false;
        }
        
        String errorMessage = error.getMessage();
        return AppConstants.Policy.DISCONNECTS.stream()
                .anyMatch(errorMessage::contains);
    }
}
package com.example.ocr.constant;

import java.util.List;

/**
 * 애플리케이션 전역에서 여러 계층(Controller, Service, DTO, Config 등)에 걸쳐
 * 공통으로 참조되는 매직 넘버 및 문자열을 관리하는 상수 클래스입니다.
 */
public final class AppConstants {

    private AppConstants() { 
        throw new UnsupportedOperationException("Constant class cannot be instantiated"); 
    }

    // ========================================================================
    // 1. 공통 및 파일 시스템
    // ========================================================================

    public static final String DEFAULT_DOC_NAME = "file";
    public static final String UNKNOWN          = "unknown";
    public static final String REPLACE_CHAR     = "_";
    public static final String INVALID_CHARS    = "[^a-zA-Z0-9.-]";
    
    /** 최대 업로드 허용 파일 크기 (300MB) */
    public static final long   MAX_FILE_SIZE    = 300 * 1024 * 1024L; 

    public static final String EXT_PNG          = ".png";
    public static final String EXT_TIFF         = "TIFF";
    public static final String EXT_TXT          = ".txt";
    public static final String MIME_PNG         = "png";

    // ========================================================================
    // 2. 도메인 및 DTO 메타 타입 
    // ========================================================================

    public static final String TYPE_RAW         = "raw";
    public static final String TYPE_DECISION    = "decision";

    // ========================================================================
    // 3. AI 모델 자원 경로
    // ========================================================================

    public static final class Model {
        private Model() {}
        
        public static final String ONNX_PATH    = "models/onnx/";
        public static final String LAYOUT_MODEL = "layout_cdla.onnx";
        public static final String LAYOUT_DICT  = "layout_dict.txt";
        public static final String[] REQUIRED   = {LAYOUT_MODEL, LAYOUT_DICT};
    }

    // ========================================================================
    // 4. 법원 결정문 파싱 필드 키워드 
    // ========================================================================

    public static final class Field {
        private Field() {}
        
        public static final String META_TYPE    = "type";
        public static final String COURT        = "법원";
        public static final String INCIDENT     = "사건";
        public static final String CREDITOR     = "채권자";
        public static final String DEBTOR       = "채무자";
        public static final String THIRD_DEBTOR = "제3채무자";
        public static final String ORDER        = "주문";
        public static final String AMOUNT       = "청구금액";
        public static final String REASON       = "이유";
    }

    // ========================================================================
    // 5. 비즈니스 정책 및 공통 규격 
    // ========================================================================

    public static final class Policy {
        private Policy() {}
        
        /** 네트워크 단절 시 발생하는 예외 메시지 목록 (GlobalExceptionHandler, SseUtil 등에서 공유) */
        public static final List<String> DISCONNECTS = List.of(
                "Broken pipe", "Connection reset", "Client disconnected", "disconnected"
        );
        
        /** 텍스트 병합 시 사용할 공백 치환자 */
        public static final String SP = "[SP]";
        
        /** OpenCV Bounding Box 변환 상수 (사각형 점 4개) */
        public static final int QUAD_PTS = 4;
        
        /** OpenCV Bounding Box 좌표 차원 (X, Y 2차원) */
        public static final int COORD_DIM = 2;
    }

    // ========================================================================
    // 6. SSE 스트리밍 통신 규격 
    // ========================================================================

    public static final class Sse {
        private Sse() {}
        
        public static final String CONNECT       = "connect";
        public static final String PAGE_RESULT   = "page-result";
        public static final String SUCCESS       = "Connected";
        public static final String THREAD_PREFIX = "sse-";
    }
}
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
        private Model() {
        }

        /** ONNX 모델 파일이 저장된 기본 경로 */
        public static final String ONNX_PATH = "models/onnx/";

        /** 레이아웃 분석용 모델 파일명 */
        public static final String LAYOUT_MODEL = "layout_cdla.onnx";

        /** 레이아웃 타입 사전 파일명 */
        public static final String LAYOUT_DICT = "layout_dict.txt";

        /** SLANet 표 구조 분석 모델 파일명 */
        public static final String SLANET_MODEL = "slanet-plus.onnx";

        /** 표 구조 분석용 사전 파일명 */
        public static final String TABLE_DICT = "table_structure_dict_ch.txt";

        /** 초기 구동 시 필수 체크 모델 자원 목록 */
        public static final List<String> REQUIRED = List.of(LAYOUT_MODEL, LAYOUT_DICT, SLANET_MODEL, TABLE_DICT);
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

        /** 수직 인접성 판단을 위한 줄 높이 배수 */
        public static final double ADJACENCY_MULTIPLIER = 1.5;
        
        /** 수직 박스 병합 시 허용하는 최소 겹침/간격 픽셀 (음수는 겹침 허용) */
        public static final int VERTICAL_GAP_TOLERANCE = -10;
        
        /** IQR 기반 이상치 탐지를 수행하기 위한 최소 샘플 수 */
        public static final int MIN_OUTLIER_SAMPLE = 3;
        
        /** IQR(사분위범위)의 가중치 계수 (표준 1.5) */
        public static final double IQR_FACTOR = 1.5;

        /** OCR 결과가 없을 때의 기본 메시지 */
        public static final String EMPTY_OCR_MSG = "Empty OCR";
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
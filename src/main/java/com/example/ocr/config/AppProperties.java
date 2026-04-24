package com.example.ocr.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

/**
 * application.yml (또는 properties)에 정의된 'ocr.*' 하위의 설정값들을 
 * 애플리케이션 내에서 타입 안전(Type-safe)하게 바인딩하여 제공하는 불변 레코드입니다.
 */
@ConfigurationProperties(prefix = "ocr")
@Validated
public record AppProperties(
        
        @Valid @DefaultValue Engine engine,
        @Valid @DefaultValue Algorithm algorithm,
        @Valid @DefaultValue Models models,
        @Valid @DefaultValue Documents documents,
        @Valid @DefaultValue Async async,
        @Valid @DefaultValue Cors cors,
        @Valid @DefaultValue Visual visual
        
) {

    // ========================================================================
    // 1. 추론 엔진 설정 
    // ========================================================================

    /**
     * OCR 텍스트 탐지 및 인식에 관련된 핵심 하이퍼파라미터 설정
     */
    public record Engine(
            
            /** 이미지 추론 시 리사이즈할 최대 변의 길이 (픽셀) */
            @DefaultValue("960") @Positive 
            int maxDetectionSideLength,

            /** 텍스트 탐지(Detection) 시 유효한 박스로 인정할 최소 신뢰도 점수 */
            @DefaultValue("0.3") @Min(0) @Max(1) 
            float detectionScoreThreshold,

            /** 텍스트 박스 병합/분리 판단 시 사용할 임계값 */
            @DefaultValue("0.5") @Min(0) @Max(1) 
            float boxScoreThreshold,

            /** 탐지된 박스 영역을 얼마나 확장할 것인지 결정하는 비율 */
            @DefaultValue("1.6") @Positive 
            float detectionUnclipRatio,

            /** 텍스트 방향 분류기(Classifier) 사용 여부 */
            @DefaultValue("false") 
            boolean useClassification,

            /** 기울어진 문서 이미지의 각도 보정 기능 사용 여부 */
            @DefaultValue("false") 
            boolean useAngleCorrection,

            /** 가장 많이 기울어진 각도를 기준으로 전체 문서를 보정할지 여부 */
            @DefaultValue("false") 
            boolean useMostAngleCorrection,

            /** 이미지 가장자리에 추가할 여백(Padding) 크기 */
            @DefaultValue("10") @Min(0) 
            int imagePadding,

            /** 레이아웃 탐지(표, 제목 등) 시 유효한 영역으로 인정할 최소 신뢰도 */
            @DefaultValue("0.3") @Min(0) @Max(1) 
            float layoutScoreThreshold
            
    ) {}

    // ========================================================================
    // 2. 파싱 및 알고리즘 설정
    // ========================================================================

    /**
     * OCR 결과를 도메인 데이터로 가공(파싱)하는 규칙 및 알고리즘 설정
     */
    public record Algorithm(
            
            /** 같은 줄(Line)로 인식하기 위해 허용하는 Y축(상하) 최대 픽셀 오차 */
            @DefaultValue("15") @Min(0) 
            int lineMergingVerticalGap,

            /** 서로 다른 문단(Section)으로 분리되었다고 판단할 Y축 최소 간격 */
            @DefaultValue("100") @Positive 
            int sectionBreakThreshold,

            /** 키워드 탐색 시(Jaro-Winkler 등) 통과시킬 최소 유사도 (0.0 ~ 1.0) */
            @DefaultValue("0.7") @Min(0) @Max(1) 
            double fuzzyMatchSimilarity,

            /** 문서 상단에서 헤더(법원명, 결정문 등)를 찾을 때 스캔할 최대 라인 수 */
            @DefaultValue("10") @Min(0) 
            int headerSearchLineLimit,

            /** '법원' 키워드를 찾기 위해 상단에서 스캔할 라인 수 제한 */
            @DefaultValue("5") @Min(0) 
            int courtHeaderSearchLimit,

            /** '법원' 키워드 발견 후 '결정' 키워드를 찾기 위해 추가로 스캔할 라인 수 */
            @DefaultValue("4") @Min(0) 
            int decisionHeaderSearchOffset,

            /** 라인 정렬 시 무시할 Y축 오차 범위 */
            @DefaultValue("12") @Min(0) 
            int lineSortingYTolerance,

            /** 레이아웃 영역 보정 시 OCR 블록과의 최소 겹침 비율 (IoU) */
            @DefaultValue("0.3") @Min(0) @Max(1)
            float layoutIouThreshold,

            /** 레이아웃 보정 시 유효한 텍스트로 인정할 최소 글자 수 */
            @DefaultValue("1") @Min(1)
            int minTextLength,

            /** 텍스트 블록의 최소 평균 인식 신뢰도 (0.0 ~ 1.0) */
            @DefaultValue("0.6") @Min(0) @Max(1)
            float minCharScore,

            /** 표(Table) 영역 보정 시 추가할 상하좌우 여백 (픽셀) */
            @DefaultValue("10") @Min(0)
            int tablePadding,

            /** 일반 텍스트 영역 보정 시 추가할 상하좌우 여백 (픽셀) */
            @DefaultValue("5") @Min(0)
            int textPadding,

            /** 디버그 디렉토리 정리(Cleanup)를 수행할 최소 주기 (밀리초) */
            @DefaultValue("3000") @Positive 
            int debugDirectoryCleanupIntervalMs,

            /** 시각화 결과물 및 임시 파일이 저장될 출력 디렉토리 경로 */
            @DefaultValue("vis") @NotEmpty 
            String debugOutputDir
            
    ) {}

    // ========================================================================
    // 3. AI 모델 관련 설정 
    // ========================================================================

    /**
     * 레이아웃, 테이블 등 특정 딥러닝 모델의 전처리 및 후처리에 필요한 파라미터
     */
    public record Models(
            
            @DefaultValue("800") @Positive 
            int layoutTargetHeight,

            /** 레이아웃 모델 입력 타겟 너비 */
            @DefaultValue("608") @Positive
            int layoutTargetWidth,

            /** 표 구조 분석 모델 입력 타겟 크기 */
            @DefaultValue("488") @Positive
            int tableTargetSize,

            /** ImageNet 표준 평균값 (Normalization용) */
            @DefaultValue({"0.485", "0.456", "0.406"}) @NotEmpty
            List<Float> imagenetMean,
            
            @DefaultValue({"0.229", "0.224", "0.225"}) @NotEmpty 
            List<Float> imagenetStd,
            
            /** 박스 추출 후 시각화 시 여백을 주기 위한 확장 비율 */
            @DefaultValue("0.10") @Min(0) @Max(1) 
            float layoutExpansionMargin,
            
            /** 중복된 레이아웃 박스를 제거하기 위한 NMS(Non-Maximum Suppression) 임계값 */
            @DefaultValue("0.45") @Min(0) @Max(1) 
            float layoutNmsThreshold,

            /** NMS 처리 전 유지할 최대 후보 영역 개수 */
            @DefaultValue("1000") @Positive
            int layoutNmsTopK
            
    ) {}

    // ========================================================================
    // 4. API 문서화 설정
    // ========================================================================

    /**
     * Swagger / OpenAPI 명세서에 노출될 애플리케이션 정보
     */
    public record Documents(
            @DefaultValue("OCR Service API") @NotEmpty 
            String title,
            
            @DefaultValue("Document Structure Analysis & Text Extraction Engine") @NotEmpty 
            String description,
            
            @DefaultValue("1.0.0") @NotEmpty 
            String version,
            
            @DefaultValue("http://localhost:8080") 
            String localUrl,
            
            @DefaultValue("Local Development") 
            String localDescription,
            
            @DefaultValue("/") 
            String currentUrl,
            
            @DefaultValue("Current Environment") 
            String currentDescription
    ) {}

    // ========================================================================
    // 5. 비동기 및 스레드 풀 설정
    // ========================================================================

    /**
     * SSE 스트리밍 및 동시 추론 요청 처리를 위한 스레드 풀 규격
     */
    public record Async(
            @DefaultValue("2") @Positive 
            int corePoolSize,
            
            @DefaultValue("4") @Positive 
            int maxPoolSize,
            
            @DefaultValue("50") @Positive 
            int queueCapacity,
            
            @DefaultValue("60") @Min(0) 
            int keepAliveSeconds,
            
            /** OCR 엔진 세마포어 대기 타임아웃 (밀리초) */
            @DefaultValue("30000") @Positive 
            long inferenceTimeoutMs,
            
            /** JVM 종료 시 진행 중인 작업을 기다려줄지 여부 (Graceful Shutdown) */
            @DefaultValue("true") 
            boolean waitForTasksOnShutdown,
            
            @DefaultValue("60") @Positive 
            int shutdownTimeoutSeconds,
            
            /** SSE 연결 최대 유지 시간 (밀리초) */
            @DefaultValue("600000") @Positive 
            long sseTimeoutMs
    ) {}

    // ========================================================================
    // 6. CORS 설정 
    // ========================================================================

    public record Cors(
            @DefaultValue("*") @NotEmpty 
            List<String> allowedOriginPatterns,
            
            @DefaultValue({"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"}) @NotEmpty 
            List<String> allowedMethods,
            
            @DefaultValue("*") @NotEmpty 
            List<String> allowedHeaders,
            
            @DefaultValue("true") 
            boolean allowCredentials,
            
            @DefaultValue("3600") @Positive 
            long maxAge
    ) {}

    // ========================================================================
    // 7. 시각화(디버그 이미지) 설정 
    // ========================================================================

    public record Visual(
            @DefaultValue("0.6") @Positive 
            double labelFontScale,
            
            @DefaultValue("2") @Positive 
            int labelThickness, 
            
            @DefaultValue("10") 
            int labelYOffset,
            
            @DefaultValue("20") 
            int labelMinY
    ) {}
}
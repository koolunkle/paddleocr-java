package com.example.ocr.config;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.ocr.service.ModelService;
import ai.onnxruntime.OrtEnvironment;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import io.github.mymonstercat.ocr.config.HardwareConfig;
import io.github.mymonstercat.ocr.config.ParamConfig;
import jakarta.annotation.PostConstruct;
import nu.pattern.OpenCV;

/**
 * AI 추론 엔진(OCR 및 레이아웃 분석) 구동에 필요한 네이티브 라이브러리(OpenCV) 로드 및 ONNX Runtime 환경 설정을 담당하는 클래스입니다.
 */
@Configuration
public class InferenceConfig {

    // ========================================================================
    // 로거 및 상태 변수
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(InferenceConfig.class);

    private final AppProperties appProperties;
    private final ModelService modelService;

    // ========================================================================
    // 생성자
    // ========================================================================

    public InferenceConfig(AppProperties appProperties, ModelService modelService) {
        this.appProperties = appProperties;
        this.modelService = modelService;
    }

    // ========================================================================
    // 초기화
    // ========================================================================

    /**
     * 애플리케이션(스프링 컨텍스트) 시작 시 OpenCV 네이티브 라이브러리를 메모리에 로드합니다. JNI 바인딩이 실패할 경우를 대비해 Throwable로 넓게 예외를
     * 잡습니다.
     */
    @PostConstruct
    public void initializeOpenCvNativeLibrary() {
        try {
            OpenCV.loadLocally();
            log.info("[InferenceConfig] Native OpenCV library loaded successfully");
        } catch (Throwable e) {
            // Error, Exception 등 모든 예외 상황 로깅
            log.error("[InferenceConfig] Failed to load native OpenCV library", e);
        }
    }

    // ========================================================================
    // Spring Bean 설정
    // ========================================================================

    /**
     * ONNX Runtime의 글로벌 환경(Environment) 객체를 생성합니다. 모든 ONNX 세션(Session)은 이 환경 객체를 바탕으로 생성되어야 합니다. *
     */
    @Bean
    OrtEnvironment onnxRuntimeEnvironment() {
        return OrtEnvironment.getEnvironment();
    }

    /**
     * V4 버전의 ONNX 모델을 기반으로 하는 OCR 추론 엔진 싱글톤 객체를 생성합니다. 하드웨어 가속(GPU/CPU) 설정이 포함됩니다.
     */
    @Bean
    InferenceEngine ocrInferenceEngine() {
        log.info("[InferenceConfig] Preparing models and Initializing V4 ONNX OCR Engine");
        try {
            modelService.prepare(Model.ONNX_PPOCR_V4);
        } catch (IOException e) {
            log.error("[InferenceConfig] Failed to prepare ONNX model", e);
            throw new RuntimeException("Failed to initialize OCR engine", e);
        }
        return InferenceEngine.getInstance(Model.ONNX_PPOCR_V4, HardwareConfig.getOnnxConfig());
    }

    /**
     * application.yml (또는 properties)에 정의된 엔진 설정값을 읽어와 OCR 추론 시 사용할 파라미터(임계값, 패딩, 각도 보정 등) 객체로
     * 매핑합니다.
     */
    @Bean
    ParamConfig ocrParameterConfiguration() {
        AppProperties.Engine engineProperties = this.appProperties.engine();
        ParamConfig parameterConfig = ParamConfig.getDefaultConfig();

        // 기울기 보정 설정
        parameterConfig.setDoAngle(engineProperties.useAngleCorrection());
        parameterConfig.setMostAngle(engineProperties.useMostAngleCorrection());

        // 탐지 크기 및 임계값 설정
        parameterConfig.setMaxSideLen(engineProperties.maxDetectionSideLength());
        parameterConfig.setBoxScoreThresh(engineProperties.boxScoreThreshold());
        parameterConfig.setBoxThresh(engineProperties.detectionScoreThreshold());

        // 영역 보정 설정
        parameterConfig.setUnClipRatio(engineProperties.detectionUnclipRatio());
        parameterConfig.setPadding(engineProperties.imagePadding());

        return parameterConfig;
    }
}

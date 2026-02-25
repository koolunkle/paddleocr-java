package com.example.ocr.support;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.ocr.constant.AppConstants;
import com.example.ocr.service.LayoutService;
import com.example.ocr.service.ModelService;

import io.github.mymonstercat.Model;

/**
 * 스프링 부트 애플리케이션의 구동이 완료된 직후, 
 * OCR 및 레이아웃 분석에 필요한 AI 모델과 사전(Dictionary) 파일들을 
 * 메모리에 미리 적재(Warm-up)하여 초기 구동 지연을 방지하는 컴포넌트입니다.
 */
@Component
public class AppInitializer {

    // ========================================================================
    // 로거 및 상태 변수 
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(AppInitializer.class);
    
    private final ModelService modelService;
    private final LayoutService layoutService;

    // ========================================================================
    // 생성자 
    // ========================================================================

    public AppInitializer(ModelService modelService, LayoutService layoutService) {
        this.modelService = modelService;
        this.layoutService = layoutService;
    }

    // ========================================================================
    // 애플리케이션 이벤트 리스너 
    // ========================================================================

    /**
     * ApplicationReadyEvent를 수신하여 코어 컴포넌트 초기화를 시작합니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeApplication() {
        try {
            log.info("[AppInitializer] Initializing core AI components...");
            
            // 1. ONNX 모델 파일들을 임시 디렉토리로 추출하고 루트 경로 반환
            Path modelRootDirectory = this.modelService.prepare(Model.ONNX_PPOCR_V4);
            
            // 2. 추출된 경로를 바탕으로 레이아웃 탐지 서비스 초기화
            initializeLayoutService(modelRootDirectory);
            
            log.info("[AppInitializer] Core components initialization complete");
            
        } catch (Exception e) {
            log.error("[AppInitializer] Failed to initialize application core components", e);
        }
    }

    // ========================================================================
    // 비공개 헬퍼 메서드 
    // ========================================================================

    /**
     * 지정된 모델 루트 경로에서 레이아웃 모델과 사전 파일을 찾아 LayoutService를 초기화합니다.
     */
    private void initializeLayoutService(Path modelRootDirectory) {
        try {
            Path layoutModelPath = modelRootDirectory.resolve(AppConstants.Model.LAYOUT_MODEL);
            Path layoutDictionaryPath = modelRootDirectory.resolve(AppConstants.Model.LAYOUT_DICT);

            // 파일이 하나라도 존재하지 않으면 초기화를 건너뛰도록 Early Return 적용
            if (!Files.exists(layoutModelPath) || !Files.exists(layoutDictionaryPath)) {
                log.warn("[AppInitializer] Layout service initialization SKIPPED: Missing required model or dictionary files");
                return;
            }

            // 파일이 정상적으로 존재하면 초기화 수행
            this.layoutService.init(layoutModelPath.toString(), Files.readAllLines(layoutDictionaryPath));
            log.info("[AppInitializer] Layout service initialized and READY");
            
        } catch (Exception e) {
            log.error("[AppInitializer] Failed to initialize Layout service: {}", e.getMessage(), e);
        }
    }
}
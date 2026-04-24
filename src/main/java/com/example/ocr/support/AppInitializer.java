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
import com.example.ocr.service.TableService;

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
    private final TableService tableService;

    // ========================================================================
    // 생성자 
    // ========================================================================

    public AppInitializer(
            ModelService modelService,
            LayoutService layoutService,
            TableService tableService) {

        this.modelService = modelService;
        this.layoutService = layoutService;
        this.tableService = tableService;
    }

    // ========================================================================
    // 애플리케이션 이벤트 리스너 
    // ========================================================================

    /**
     * ApplicationReadyEvent를 수신하여 코어 컴포넌트 초기화를 시작합니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            log.info("Initializing core AI components...");

            // 1. ONNX 모델 파일들을 임시 디렉토리로 추출하고 루트 경로 반환
            Path modelRoot = this.modelService.prepare(Model.ONNX_PPOCR_V4);

            // 2. 레이아웃 탐지 서비스 초기화
            initLayoutService(modelRoot);

            // 3. 표 구조 분석 서비스 초기화
            initTableService(modelRoot);

            log.info("Core components initialization complete");
        } catch (Exception e) {
            log.error("Failed to initialize core AI components", e);
        }
    }

    // ========================================================================
    // 비공개 헬퍼 메서드 
    // ========================================================================

    /**
     * 레이아웃 탐지 모델과 사전 파일을 로드하여 LayoutService를 초기화합니다.
     */
    private void initLayoutService(Path modelRoot) {
        Path modelPath = modelRoot.resolve(AppConstants.Model.LAYOUT_MODEL);
        Path dictPath = modelRoot.resolve(AppConstants.Model.LAYOUT_DICT);

        if (!Files.exists(modelPath) || !Files.exists(dictPath)) {
            log.warn("Skipping LayoutService init: required files not found");
            return;
        }

        try {
            this.layoutService.init(modelPath.toString(), Files.readAllLines(dictPath));
            log.info("LayoutService has been initialized");
        } catch (Exception e) {
            log.error("LayoutService initialization failed: {}", e.getMessage());
        }
    }

    /**
     * 표 구조 분석 모델과 사전 파일을 로드하여 TableService를 초기화합니다.
     */
    private void initTableService(Path modelRoot) {
        Path modelPath = modelRoot.resolve(AppConstants.Model.SLANET_MODEL);
        String dictResourcePath = AppConstants.Model.ONNX_PATH + AppConstants.Model.TABLE_DICT;

        if (!Files.exists(modelPath)) {
            log.warn("Skipping TableService init: model file not found");
            return;
        }

        try {
            this.tableService.init(modelPath.toString(), dictResourcePath);
            log.info("TableService has been initialized");
        } catch (Exception e) {
            log.error("TableService initialization failed: {}", e.getMessage());
        }
    }
}
package com.example.ocr.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.ocr.config.AppProperties;
import com.example.ocr.constant.AppConstants;
import com.example.ocr.dto.AnalysisResponse;
import com.example.ocr.service.ProcessorService;
import com.example.ocr.support.SseSessionWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 클라이언트로부터 이미지/문서 파일을 업로드 받아 OCR 구조 분석을 수행하는 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/ocr")
@Tag(name = "OCR", description = "Document structure analysis API")
public class DocumentController {

    // ========================================================================
    // 상수 및 로거
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    /** 임시 파일 생성 시 사용할 접두어 (동기 처리용) */
    private static final String PREFIX_SYNC = "ocr_s_";
    
    /** 임시 파일 생성 시 사용할 접두어 (스트리밍 처리용) */
    private static final String PREFIX_STREAM = "ocr_st_";

    // ========================================================================
    // 상태 변수 
    // ========================================================================

    private final ProcessorService processorService;
    private final AsyncTaskExecutor sseTaskExecutor;
    private final AppProperties appProperties;

    // ========================================================================
    // 생성자 
    // ========================================================================

    public DocumentController(
            ProcessorService processorService,
            @Qualifier("sseExecutor") AsyncTaskExecutor sseTaskExecutor, 
            AppProperties appProperties) {
        
        this.processorService = processorService;
        this.sseTaskExecutor = sseTaskExecutor;
        this.appProperties = appProperties;
    }

    // ========================================================================
    // 공개 API 엔드포인트 
    // ========================================================================

    /**
     * [동기 방식] 업로드된 문서의 전체 페이지 분석이 끝날 때까지 대기한 후 한 번에 결과를 반환합니다.
     */
    @Operation(summary = "Analyze Image (Sync)")
    @ApiResponse(responseCode = "200", description = "Success")
    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisResponse> analyzeSynchronously(
            @RequestPart("file") MultipartFile uploadedFile,
            @RequestParam(value = "pages", required = false) String targetPagesCsv) throws IOException {

        validateUploadedFile(uploadedFile);
        
        String documentName = extractSafeFileName(uploadedFile);
        File temporaryFile = createTemporaryFile(PREFIX_SYNC, documentName);

        try {
            uploadedFile.transferTo(Objects.requireNonNull(temporaryFile.toPath()));
            
            AnalysisResponse response = this.processorService.process(
                    temporaryFile, 
                    parseTargetPageNumbers(targetPagesCsv), 
                    documentName
            );
            return ResponseEntity.ok(response);
            
        } finally {
            cleanupTemporaryFile(temporaryFile);
        }
    }

    /**
     * [스트리밍 방식] SSE를 활용하여 페이지별 분석이 끝나는 즉시 클라이언트에게 이벤트를 푸시합니다.
     */
    @Operation(summary = "Analyze Image (Streaming)")
    @PostMapping(value = "/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeViaStreaming(
            @RequestPart("file") MultipartFile uploadedFile,
            @RequestParam(value = "pages", required = false) String targetPagesCsv) throws IOException {

        validateUploadedFile(uploadedFile);
        
        String documentName = extractSafeFileName(uploadedFile);
        SseSessionWrapper sseSession = new SseSessionWrapper(this.appProperties.async().sseTimeoutMs(), documentName);
        File temporaryFile = createTemporaryFile(PREFIX_STREAM, documentName);

        uploadedFile.transferTo(Objects.requireNonNull(temporaryFile.toPath()));

        // 별도의 스레드풀에서 비동기로 OCR 분석 수행
        this.sseTaskExecutor.execute(() -> {
            try {
                // 클라이언트에게 연결 성공 알림 전송
                sseSession.send(AppConstants.Sse.CONNECT, AppConstants.Sse.SUCCESS);
                
                // 페이지별 분석이 완료될 때마다 Consumer를 통해 SSE 이벤트 전송
                this.processorService.run(
                        temporaryFile, 
                        parseTargetPageNumbers(targetPagesCsv), 
                        documentName,
                        pageResult -> sseSession.send(AppConstants.Sse.PAGE_RESULT, pageResult)
                );
                
                sseSession.close();
                
            } catch (Exception e) {
                log.error("[DocumentController] Streaming analysis failed for document: {}", documentName, e);
                sseSession.closeWithError(e);
                
            } finally {
                cleanupTemporaryFile(temporaryFile);
            }
        });

        // Controller는 즉시 Emitter 객체를 반환하여 HTTP 연결을 열어둠
        return sseSession.getEmitter();
    }

    // ========================================================================
    // 비공개 헬퍼 메서드 
    // ========================================================================

    /**
     * 쉼표(,)로 구분된 페이지 번호 문자열을 정수 리스트로 파싱합니다.
     */
    private List<Integer> parseTargetPageNumbers(String pageNumbersCsv) {
        if (pageNumbersCsv == null || pageNumbersCsv.isBlank()) {
            return null;
        }
        
        try {
            return Arrays.stream(pageNumbersCsv.split(","))
                    .map(String::trim)
                    .filter(numberString -> !numberString.isEmpty())
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid page number format: " + pageNumbersCsv);
        }
    }

    /**
     * 업로드된 파일의 유효성(존재 여부, 용량 초과 등)을 검증합니다.
     */
    private void validateUploadedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is missing or empty");
        }
        if (file.getSize() > AppConstants.MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Uploaded file exceeds the maximum allowed size");
        }
    }

    /**
     * 파일명이 없을 경우 기본 문서 이름을 반환하여 NullPointerException을 방지합니다.
     */
    private String extractSafeFileName(MultipartFile file) {
        return Optional.ofNullable(file.getOriginalFilename())
                .orElse(AppConstants.DEFAULT_DOC_NAME);
    }

    /**
     * OCR 분석을 수행할 디스크 상의 임시 파일을 생성합니다.
     */
    private File createTemporaryFile(String prefix, String fileName) throws IOException {
        return Files.createTempFile(prefix, AppConstants.REPLACE_CHAR + fileName).toFile();
    }

    /**
     * 분석이 끝난 임시 파일을 안전하게 삭제합니다.
     */
    private void cleanupTemporaryFile(File temporaryFile) {
        if (temporaryFile != null && temporaryFile.exists()) {
            boolean isDeleted = temporaryFile.delete();
            if (!isDeleted) {
                // 당장 삭제가 안 되면 JVM 종료 시점에 삭제되도록 예약
                temporaryFile.deleteOnExit();
            }
        }
    }
}
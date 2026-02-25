package com.example.ocr.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.example.ocr.constant.AppConstants;
import io.github.mymonstercat.Model;

/**
 * AI 모델 리소스(ONNX 모델, 설정 파일 등)를 애플리케이션 클래스패스에서 파일 시스템의 임시 디렉토리로 추출하고 관리하는 서비스
 */
@Service
public class ModelService {

    // ========================================================================
    // 상수 및 로거
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    // ========================================================================
    // 공개 메서드
    // ========================================================================

    /**
     * 모델 추론 엔진이 사용할 수 있도록 클래스패스 내장 모델 파일들을 로컬 파일 시스템(임시 폴더)으로 추출하여 준비합니다.
     *
     * @param model 준비할 AI 모델 정보 객체
     * @return 모델 파일들이 추출된 임시 디렉토리의 루트 경로
     * @throws IOException 임시 디렉토리 생성 실패 시 발생
     */
    public Path prepare(Model model) throws IOException {
        Path targetDirectory = Path.of(model.getTempDirPath()).toAbsolutePath();

        Files.createDirectories(targetDirectory);

        // 1. 모델 전용 파일 추출 (예: .onnx 파일, 라벨 텍스트 등)
        String modelResourcePrefix = model.getModelsDir() + "/";
        extractFiles(modelResourcePrefix, targetDirectory, model.getModelFileArray());

        // 2. OCR 엔진 구동에 공통으로 필요한 필수 파일 추출
        extractFiles(AppConstants.Model.ONNX_PATH, targetDirectory, AppConstants.Model.REQUIRED);

        return targetDirectory;
    }

    // ========================================================================
    // 비공개 메서드
    // ========================================================================

    /**
     * 지정된 파일 목록을 클래스패스에서 읽어 로컬 타겟 디렉토리로 복사(추출)합니다.
     */
    private void extractFiles(String classpathPrefix, Path targetDirectory, String[] fileNames) {
        Arrays.stream(fileNames).filter(fileName -> fileName != null && !fileName.isEmpty())
                .forEach(fileName -> {
                    try {
                        Path targetFilePath = targetDirectory.resolve(fileName);

                        // 이미 존재하고 덮어쓸 필요가 없는 무거운 파일은 건너뜀
                        if (shouldSkipExtraction(targetFilePath, fileName)) {
                            return;
                        }

                        String classpathResourcePath = classpathPrefix + fileName;
                        copyResourceToFile(classpathResourcePath, targetFilePath);

                    } catch (IOException e) {
                        log.error("[ModelService] Failed to extract model file: {}", fileName, e);
                    }
                });
    }

    /**
     * 파일 추출(복사)을 건너뛸지 여부를 결정합니다. TXT 파일은 설정 변경 가능성 등을 고려해 매번 덮어쓰도록 유도하고, 그 외 파일(주로 무거운 모델 바이너리)은 이미
     * 존재하면 건너뜁니다.
     */
    private boolean shouldSkipExtraction(Path targetFilePath, String fileName) {
        boolean fileExists = Files.exists(targetFilePath);
        boolean isTextFile = fileName.endsWith(AppConstants.EXT_TXT);

        return fileExists && !isTextFile;
    }

    /**
     * 단일 클래스패스 리소스를 로컬 파일 시스템으로 복사합니다.
     */
    private void copyResourceToFile(String classpathResourcePath, Path targetFilePath)
            throws IOException {
        try (InputStream resourceStream =
                getClass().getClassLoader().getResourceAsStream(classpathResourcePath)) {

            if (resourceStream == null) {
                throw new FileNotFoundException(
                        "Resource not found in classpath: " + classpathResourcePath);
            }

            Files.copy(resourceStream, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("[ModelService] Successfully extracted resource: {}",
                    targetFilePath.getFileName());
        }
    }
}

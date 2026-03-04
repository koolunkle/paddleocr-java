package com.example.ocr.service;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ocr.config.AppProperties;
import com.example.ocr.constant.AppConstants;
import com.example.ocr.domain.DecisionField;
import com.example.ocr.domain.LayoutType;
import com.example.ocr.domain.Visualizable;
import com.example.ocr.support.MatResourceWrapper; 

/**
 * OCR 분석 결과(영역, 레이아웃, 신뢰도 등)를 원본 이미지 위에 시각화하여 
 * 디버깅용 파일로 저장하는 서비스
 */
@Service
public class VisualService {

    // ========================================================================
    // 상수 및 로거 
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(VisualService.class);
    
    /** 기본 색상 (타입 분류 실패 또는 매칭되는 색상이 없을 때 사용될 자홍색) */
    private static final double[] DEFAULT_MAGENTA_COLOR = {255.0, 0.0, 255.0};
    
    /** 박스 테두리 두께 */
    private static final int BORDER_THICKNESS = 2;
    
    /** 내부 채우기 투명도 (0.0: 완전 투명, 1.0: 불투명) */
    private static final double OVERLAY_ALPHA = 0.3;
    
    /** 텍스트 라벨 출력 포맷 (예: "TITLE (0.95)") */
    private static final String LABEL_FORMAT = "%s (%.2f)";

    /** 디버그 출력 디렉토리 및 파일명 패턴 */
    private static final String DIR_DECISION = "decision";
    private static final String DIR_LAYOUT = "layout";
    private static final String FILE_DECISION_PATTERN = "decision_p%03d" + AppConstants.EXT_PNG;
    private static final String FILE_LAYOUT_PATTERN = "layout_p%03d" + AppConstants.EXT_PNG;

    // ========================================================================
    // 상태 변수
    // ========================================================================

    private final AppProperties appProperties;
    
    /** * 문서별 마지막 디렉토리 정리(Cleanup) 시간을 기록하여 
     * 짧은 시간 내에 불필요한 삭제 및 생성(I/O)이 반복되는 것을 방지합니다. 
     */
    private final Map<String, Long> lastCleanupTimeMap = new ConcurrentHashMap<>();

    // ========================================================================
    // 생성자
    // ========================================================================

    public VisualService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // ========================================================================
    // 공개 메서드 
    // ========================================================================

    /**
     * 특정 문서의 디버그 출력 디렉토리를 정리(초기화)합니다.
     * 설정된 간격(Cleanup Interval) 내에는 중복 실행되지 않습니다.
     *
     * @param documentName 문서(또는 작업) 식별 이름
     */
    public void clearDebugDirectory(String documentName) {
        String sanitizedDocumentName = sanitizeFileName(documentName);
        long currentTime = System.currentTimeMillis();
        long lastCleanupTime = this.lastCleanupTimeMap.getOrDefault(sanitizedDocumentName, 0L);
        long cleanupInterval = this.appProperties.algorithm().debugDirectoryCleanupIntervalMs();

        // 정리 주기가 아직 지나지 않았다면 스킵 (Early Return)
        if (currentTime - lastCleanupTime < cleanupInterval) {
            return;
        }

        try {
            Path rootPath = Paths.get(this.appProperties.algorithm().debugOutputDir(), sanitizedDocumentName);
            deleteDirectoryRecursively(rootPath);
            createDirectories(rootPath.resolve(DIR_DECISION), rootPath.resolve(DIR_LAYOUT));
            
            this.lastCleanupTimeMap.put(sanitizedDocumentName, currentTime);
        } catch (Exception e) {
            log.warn("[VisualService] Failed to cleanup debug directory for: {}", documentName, e);
        }
    }

    /**
     * 법원 결정문(Decision) 필드 추출 결과를 이미지에 그리고 디버그 디렉토리에 저장합니다.
     */
    public void saveDecisionDebug(Mat sourceImage, int pageNumber, String documentName, Map<String, List<Rect>> extractedDecisionFields) {
        if (isInvalidImage(sourceImage)) {
            return;
        }

        // clone()으로 파생된 Mat 객체들을 wrapper로 묶어 자동 해제되도록 구성
        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            Mat canvas = wrapper.add(sourceImage.clone());
            Mat overlay = wrapper.add(sourceImage.clone());

            extractedDecisionFields.forEach((fieldType, boundingBoxes) -> {
                Scalar color = resolveColor(fieldType);
                boundingBoxes.forEach(box -> drawBoundingBox(canvas, overlay, box, color));
            });

            String fileName = FILE_DECISION_PATTERN.formatted(pageNumber);
            applyOverlayAndSave(canvas, overlay, documentName, DIR_DECISION, fileName);
            
        } 
    }

    /**
     * 레이아웃 분석 결과를 텍스트 라벨과 함께 이미지에 그리고 저장합니다.
     */
    public void saveLayoutDebug(Mat sourceImage, int pageNumber, String documentName, List<LayoutService.LayoutRegion> layoutRegions) {
        if (isInvalidImage(sourceImage)) {
            return;
        }

        // MatResourceWrapper를 통해 생성된 메모리 자동 관리
        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            Mat canvas = wrapper.add(sourceImage.clone());
            Mat overlay = wrapper.add(sourceImage.clone());

            layoutRegions.forEach(region -> {
                Scalar color = resolveColor(region.type());
                Rect openCvRect = convertToOpenCvRect(region.rect());
                
                drawBoundingBox(canvas, overlay, openCvRect, color);
                drawTextLabel(canvas, region, color);
            });

            String fileName = FILE_LAYOUT_PATTERN.formatted(pageNumber);
            applyOverlayAndSave(canvas, overlay, documentName, DIR_LAYOUT, fileName);
        }
    }

    // ========================================================================
    // 비공개 메서드 - OpenCV 시각화
    // ========================================================================

    /**
     * 테두리가 있는 반투명한 사각형 박스를 그립니다.
     */
    private void drawBoundingBox(Mat canvas, Mat overlay, Rect boundingBox, Scalar color) {
        Point topLeft = new Point(boundingBox.x, boundingBox.y);
        Point bottomRight = new Point(boundingBox.x + boundingBox.width, boundingBox.y + boundingBox.height);
        
        // overlay에는 내부를 꽉 채운 사각형을 그림 (-1: 채우기)
        Imgproc.rectangle(overlay, topLeft, bottomRight, color, -1);
        
        // canvas에는 선명한 테두리 선(Anti-Aliasing)을 그림
        Imgproc.rectangle(canvas, topLeft, bottomRight, color, BORDER_THICKNESS, Imgproc.LINE_AA);
    }

    /**
     * 인식된 영역 위에 레이아웃 타입과 신뢰도(Score)를 나타내는 텍스트 라벨을 그립니다.
     */
    private void drawTextLabel(Mat canvas, LayoutService.LayoutRegion region, Scalar color) {
        String labelText = LABEL_FORMAT.formatted(region.type(), region.score());
        int font = Imgproc.FONT_HERSHEY_SIMPLEX;
        double scale = this.appProperties.visual().labelFontScale();
        int thickness = this.appProperties.visual().labelThickness();
        int[] baseline = new int[1];

        // 1. 텍스트 크기 및 전체 라벨 높이 계산
        Size textSize = Imgproc.getTextSize(labelText, font, scale, thickness, baseline);
        int labelHeight = (int) textSize.height + baseline[0];
        int labelWidth = (int) textSize.width;

        // 2. 배치 위치 결정 (기본값: 박스 좌상단)
        // 이미지 상단 공간이 부족하면 박스 내부 상단에 배치하여 겹침 방지
        int x = region.rect().x;
        boolean hasSpaceAbove = region.rect().y >= labelHeight;
        int baseY = hasSpaceAbove ? region.rect().y : region.rect().y + labelHeight;

        Point boxTopLeft = new Point(x, baseY - labelHeight);
        Point boxBottomRight = new Point(x + labelWidth, baseY);
        Point textOrigin = new Point(x, baseY - baseline[0]);

        // 3. 배경 박스 그리기 
        Imgproc.rectangle(canvas, boxTopLeft, boxBottomRight, color, -1);

        // 4. 텍스트 출력
        Imgproc.putText(
                canvas, 
                labelText, 
                textOrigin, 
                font, 
                scale, 
                new Scalar(255, 255, 255), 
                thickness, 
                Imgproc.LINE_AA
        );
    }

    /**
     * 반투명 오버레이를 캔버스에 합성(addWeighted)한 뒤 지정된 경로에 파일로 저장합니다.
     */
    private void applyOverlayAndSave(Mat canvas, Mat overlay, String documentName, String subDirectory, String fileName) {
        // 투명도(Alpha)를 적용하여 이미지 합성 (in-place로 canvas에 덮어씀)
        Core.addWeighted(overlay, OVERLAY_ALPHA, canvas, 1.0 - OVERLAY_ALPHA, 0, canvas);
        
        try {
            Path savePath = Paths.get(this.appProperties.algorithm().debugOutputDir(), sanitizeFileName(documentName), subDirectory, fileName);
            Files.createDirectories(savePath.getParent());
            Imgcodecs.imwrite(savePath.toString(), canvas);
        } catch (IOException e) {
            log.error("[VisualService] Failed to save debug image: {}", fileName, e);
        }
    }

    /**
     * 도메인 타입 문자열로부터 사전에 정의된 색상(Visualizable 구현체의 BGR)을 찾습니다.
     */
    private Scalar resolveColor(String typeCode) {
        return Optional.ofNullable(typeCode)
                .map(LayoutType::fromCode)
                .filter(type -> type != LayoutType.UNKNOWN)
                .map(type -> (Visualizable) type)
                .or(() -> DecisionField.fromKey(typeCode).map(field -> (Visualizable) field))
                .map(visualizable -> new Scalar(visualizable.getColorComponents()))
                .orElseGet(() -> new Scalar(DEFAULT_MAGENTA_COLOR));
    }

    // ========================================================================
    // 비공개 메서드 - 유틸리티 및 I/O
    // ========================================================================

    /**
     * 디렉토리와 그 하위의 모든 파일을 재귀적으로 삭제합니다.
     */
    private void deleteDirectoryRecursively(Path targetDirectory) throws IOException {
        if (!Files.exists(targetDirectory)) {
            return;
        }
        
        try (Stream<Path> pathStream = Files.walk(targetDirectory)) {
            pathStream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // 삭제 실패 시 무시하고 다음 파일 진행
                }
            });
        }
    }

    private void createDirectories(Path... paths) throws IOException {
        for (Path path : paths) {
            Files.createDirectories(path);
        }
    }

    private Rect convertToOpenCvRect(Rectangle awtRectangle) {
        return new Rect(awtRectangle.x, awtRectangle.y, awtRectangle.width, awtRectangle.height);
    }

    private boolean isInvalidImage(Mat imageMatrix) {
        return imageMatrix == null || imageMatrix.empty();
    }

    /**
     * 파일 시스템에서 오류를 일으킬 수 있는 문자를 안전한 문자로 치환합니다.
     */
    private String sanitizeFileName(String fileName) {
        return Optional.ofNullable(fileName)
                .map(name -> {
                    // 확장자 제거 
                    int lastDotIndex = name.lastIndexOf('.');
                    String baseName = (lastDotIndex > 0) ? name.substring(0, lastDotIndex) : name;
                    // 특수 문자 치환
                    return baseName.replaceAll(AppConstants.INVALID_CHARS, AppConstants.REPLACE_CHAR);
                })
                .orElse(AppConstants.UNKNOWN);
    }
}
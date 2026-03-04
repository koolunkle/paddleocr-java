package com.example.ocr.service;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.springframework.stereotype.Service;
import com.benjaminwan.ocrlibrary.OcrResult;
import com.benjaminwan.ocrlibrary.Point;
import com.benjaminwan.ocrlibrary.TextBlock;
import com.example.ocr.config.AppProperties;
import com.example.ocr.constant.AppConstants;
import com.example.ocr.domain.LayoutType;
import com.example.ocr.dto.AnalysisResponse;
import com.example.ocr.dto.PageData;
import com.example.ocr.exception.ProcessingException;
import com.example.ocr.exception.ServiceUnavailableException;
import com.example.ocr.parser.DecisionParser;
import com.example.ocr.parser.TableStructureEstimator;
import com.example.ocr.support.MatResourceWrapper;
import com.example.ocr.support.ResultLogger;
import com.example.ocr.util.TextUtil;
import io.github.mymonstercat.ocr.InferenceEngine;
import io.github.mymonstercat.ocr.config.ParamConfig;

/**
 * OCR, 레이아웃 분석 및 데이터 가공을 조율하는 통합 처리 서비스
 * 이미지 파싱부터 최종 분석 결과 도출까지의 전체 파이프라인을 관리합니다.
 */
@Service
public class ProcessorService {

    // ========================================================================
    // 상수 
    // ========================================================================

    private static final String PAGE_PREFIX = "ocr_p";

    // ========================================================================
    // 상태 변수
    // ========================================================================

    private final DecisionParser decisionParser;
    private final TableStructureEstimator tableStructureEstimator;
    private final AppProperties appProperties;
    private final ParamConfig paramConfig;
    private final InferenceEngine inferenceEngine;
    private final ResultLogger resultLogger;
    private final VisualService visualService;
    private final LayoutService layoutService;
    
    private final Semaphore concurrencyLimitSemaphore;

    // ========================================================================
    // 정적 초기화 
    // ========================================================================

    static {
        ImageIO.setUseCache(false);
    }

    // ========================================================================
    // 생성자 
    // ========================================================================

    public ProcessorService(
            DecisionParser decisionParser, 
            TableStructureEstimator tableStructureEstimator,
            AppProperties appProperties, 
            ParamConfig paramConfig,
            InferenceEngine inferenceEngine, 
            ResultLogger resultLogger, 
            VisualService visualService,
            LayoutService layoutService) {
        
        this.decisionParser = decisionParser;
        this.tableStructureEstimator = tableStructureEstimator;
        this.appProperties = appProperties;
        this.paramConfig = paramConfig;
        this.inferenceEngine = inferenceEngine;
        this.resultLogger = resultLogger;
        this.visualService = visualService;
        this.layoutService = layoutService;
        this.concurrencyLimitSemaphore = new Semaphore(appProperties.async().corePoolSize());
    }

    // ========================================================================
    // 공개 메서드 
    // ========================================================================

    /**
     * 이미지 파일을 분석하여 최종 응답 객체 생성
     */
    public AnalysisResponse process(File imageFile, List<Integer> targetPageNumbers, String documentName) throws IOException {
        List<AnalysisResponse.PageResult> pageResults = new ArrayList<>();
        run(imageFile, targetPageNumbers, documentName, pageResults::add);

        return new AnalysisResponse(documentName, pageResults);
    }

    /**
     * 이미지 파일을 페이지별로 순회하며 분석 실행
     */
    public void run(File imageFile, List<Integer> targetPageNumbers, String documentName, Consumer<AnalysisResponse.PageResult> resultConsumer) throws IOException {
        String safeDocumentName = Objects.requireNonNullElse(documentName, AppConstants.DEFAULT_DOC_NAME);
        this.visualService.clearDebugDirectory(safeDocumentName);

        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(imageFile)) {
            ImageReader imageReader = ImageIO.getImageReaders(imageInputStream).next();
            
            try {
                imageReader.setInput(imageInputStream, true, false);
                int totalImageCount = imageReader.getNumImages(false);

                for (int imageIndex = 0; imageIndex < totalImageCount; imageIndex++) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    int pageNumber = imageIndex + 1;

                    // 지정된 페이지 번호만 처리
                    if (targetPageNumbers == null || targetPageNumbers.contains(pageNumber)) {
                        AnalysisResponse.PageResult pageResult = processSinglePage(imageReader, imageIndex, pageNumber, safeDocumentName);
                        resultConsumer.accept(pageResult);
                    }
                }
            } finally {
                imageReader.dispose();
            }
        }
    }

    // ========================================================================
    // 비공개 메서드 - 파이프라인 흐름
    // ========================================================================
    
    /**
     * 단일 페이지를 분석하기 위해 이미지를 로드하고 처리 로직 호출
     */
    private AnalysisResponse.PageResult processSinglePage(ImageReader imageReader, int imageIndex, int pageNumber, String documentName) {
        File temporaryImageFile = null;

        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            BufferedImage bufferedImage = imageReader.read(imageIndex);
            temporaryImageFile = saveTemporaryImage(bufferedImage, pageNumber);

            // OpenCV 처리를 위해 Mat 객체로 변환
            Mat imageMat = wrapper.add(convertToMat(bufferedImage));

            return executeAnalysis(imageMat, temporaryImageFile.getAbsolutePath(), pageNumber, documentName);            
        } catch (Exception e) {
            throw new ProcessingException("Failed to process page: " + pageNumber, e);
        } finally {
            cleanupTemporaryFile(temporaryImageFile);
        }
    }

    /**
     * 핵심 분석 로직 실행: OCR 추론 및 레이아웃 탐지/보정 수행
     */
    private AnalysisResponse.PageResult executeAnalysis(Mat imageMat, String imagePath, int pageNumber, String documentName) throws InterruptedException {
        long timeoutMs = this.appProperties.async().inferenceTimeoutMs();

        if (!this.concurrencyLimitSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new ServiceUnavailableException();
        }
        
        try {
            // 1. 전체 페이지 OCR 실행
            OcrResult ocrResult = this.inferenceEngine.runOcr(imagePath, this.paramConfig);

            // 2. 레이아웃 영역 탐지 실행
            List<LayoutService.LayoutRegion> layoutRegions = this.layoutService.detectRegions(imageMat, this.appProperties.engine().layoutScoreThreshold());

            // 3. 레이아웃 영역을 OCR 텍스트 라인에 맞춰 정밀 보정 
            List<LayoutService.LayoutRegion> adjustedRegions = adjustLayoutRegions(layoutRegions, ocrResult.getTextBlocks(), imageMat.width(), imageMat.height());
            this.visualService.saveLayoutDebug(imageMat, pageNumber, documentName, adjustedRegions);

            // 4. 분석 결과 통합 및 가공
            AnalysisResponse.PageResult finalResult = buildPageResult(pageNumber, ocrResult, imageMat, documentName, adjustedRegions);

            this.resultLogger.logPageResult(finalResult);
            
            return finalResult;
        } finally {
            this.concurrencyLimitSemaphore.release();
        }
    }

    /**
     * 탐지된 레이아웃 박스를 실제 포함된 OCR 텍스트 블록들의 영역으로 정밀 보정
     */
    private List<LayoutService.LayoutRegion> adjustLayoutRegions(List<LayoutService.LayoutRegion> regions, List<TextBlock> textBlocks, int imgWidth, int imgHeight) {
        if (textBlocks == null || textBlocks.isEmpty()) {
            return Collections.emptyList();
        }

        List<LayoutService.LayoutRegion> refinedRegions = new ArrayList<>();
        float layoutIouThreshold = 0.3f; 

        for (LayoutService.LayoutRegion region : regions) {
            // 레이아웃 영역 내에 포함된 OCR 텍스트 블록 필터링
            // 유의미한 텍스트(2글자 이상)만 후보로 인정하여 미세 노이즈 차단
            List<Rectangle> containedRects = textBlocks.stream()
                    .filter(block -> {
                        String text = Objects.requireNonNullElse(block.getText(), "").trim();

                        if (text.length() < 2) {
                            return false;
                        }

                        Rectangle o = convertToAwtRectangle(block);
                        Rectangle intersection = region.rect().intersection(o);

                        if (intersection.width <= 0 || intersection.height <= 0) {
                            return false;
                        }

                        float intersectionArea = (float) intersection.width * intersection.height;
                        float oArea = (float) o.width * o.height;

                        return (intersectionArea / oArea) > layoutIouThreshold;
                    })
                    .map(this::convertToAwtRectangle)
                    .toList();

            if (containedRects.isEmpty()) {
                // 유효한 텍스트가 없는 영역은 노이즈로 간주하여 제거
                continue;
            }

            // 영역 내 모든 텍스트 블록을 포함하는 최소 외접 사각형(Union) 계산
            Rectangle ocrUnion = containedRects.stream().reduce(Rectangle::union).get();
            Rectangle finalRect;

            if (LayoutType.fromCode(region.type()).isTable()) {
                // 표(Table)는 테두리선 보존을 위해 원본 탐지 박스와 OCR 박스의 합집합 사용 + 패딩 10px
                finalRect = region.rect().union(ocrUnion);
                finalRect = applyPadding(finalRect, 10, imgWidth, imgHeight);
            } else {
                // 일반 텍스트 영역은 실제 OCR 글자가 있는 위치로 축축/최적화 + 패딩 5px
                finalRect = applyPadding(ocrUnion, 5, imgWidth, imgHeight);
            }

            refinedRegions.add(new LayoutService.LayoutRegion(region.type(), finalRect, region.score()));
        }

        return refinedRegions;
    }

    /**
     * 사각형 영역에 상하좌우 패딩 적용
     */
    private Rectangle applyPadding(Rectangle rect, int padding, int maxWidth, int maxHeight) {
        int x = Math.max(0, rect.x - padding);
        int y = Math.max(0, rect.y - padding);
        int w = Math.min(maxWidth - x, rect.width + (rect.x - x) + padding);
        int h = Math.min(maxHeight - y, rect.height + (rect.y - y) + padding);

        return new Rectangle(x, y, w, h);
    }

    /**
     * 분석 데이터를 최종 응답 형식으로 조립
     */
    private AnalysisResponse.PageResult buildPageResult(int pageNumber, OcrResult ocrResult, Mat imageMat, String documentName, List<LayoutService.LayoutRegion> layoutRegions) {
        if (ocrResult == null || ocrResult.getTextBlocks() == null) {
            return new AnalysisResponse.PageResult(pageNumber, AnalysisResponse.Type.RAW, new PageData.Raw(List.of(), "Empty OCR"));
        }

        List<TextBlock> textBlocks = ocrResult.getTextBlocks();

        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            List<Mat> boundingBoxMatrices = convertToMatList(textBlocks, wrapper);

            // 1. 특정 양식(판결문 등) 파싱 시도
            return Optional.ofNullable(this.decisionParser.parse(
                            textBlocks.stream().map(TextBlock::getText).toList(),
                            boundingBoxMatrices
                    ))
                    .map(decisionData -> {
                        this.visualService.saveDecisionDebug(imageMat, pageNumber, documentName, decisionData.boundingBoxes());
                        return new AnalysisResponse.PageResult(pageNumber, AnalysisResponse.Type.DECISION, new PageData.Decision(decisionData.data()));
                    })
                    // 2. 특정 양식이 아니면 일반(Raw) 레이아웃 결과로 반환
                    .orElseGet(() -> buildRawResult(pageNumber, textBlocks, layoutRegions));
        }
    }

    /**
     * 일반적인 문서 구조를 가진 페이지의 분석 결과 생성 (표 구조 추정 포함)
     */
    private AnalysisResponse.PageResult buildRawResult(int pageNumber, List<TextBlock> textBlocks, List<LayoutService.LayoutRegion> layoutRegions) {
        List<PageData.Region> parsedRegions = layoutRegions.stream().map(layoutRegion -> {
            // 해당 영역 내에 중심점이 포함된 텍스트 블록 필터링
            List<TextBlock> innerBlocks = textBlocks.stream()
                    .filter(block -> layoutRegion.rect().contains(calculateCenterPoint(block.getBoxPoint())))
                    .toList();

            // 텍스트를 상단->하단, 좌측->우측 순서로 정렬 및 정제
            List<String> textLines = innerBlocks.stream()
                    .sorted(this::compareTextBlockPosition)
                    .map(block -> TextUtil.sanitize(block.getText()))
                    .toList();

            // 표 영역인 경우 내부 셀 구조 추정
            PageData.TableStructure tableStructure = null;

            if (LayoutType.fromCode(layoutRegion.type()).isTable()) {
                tableStructure = this.tableStructureEstimator.estimate(innerBlocks);
            }

            return new PageData.Region(layoutRegion.type(), layoutRegion.score(), convertToDomainRect(layoutRegion.rect()), textLines, tableStructure);
        }).toList();

        return new AnalysisResponse.PageResult(pageNumber, AnalysisResponse.Type.RAW, new PageData.Raw(parsedRegions, null));
    }

    // ========================================================================
    // 비공개 메서드 - 유틸리티
    // ========================================================================

    /**
     * 두 텍스트 블록의 시각적 위치 비교 (Y좌표 우선, 동일 행이면 X좌표 순)
     */
    private int compareTextBlockPosition(TextBlock block1, TextBlock block2) {
        Rectangle rect1 = convertToAwtRectangle(block1);
        Rectangle rect2 = convertToAwtRectangle(block2);

        // 줄 높이의 절반 정도를 오차 범위(Tolerance)로 설정하여 동일 행 판단
        List<Integer> heights = List.of(rect1.height, rect2.height).stream().sorted().toList();
        int tolerance = heights.get(heights.size() / 2) / 2;

        if (Math.abs(rect1.y - rect2.y) <= tolerance) {
            return Integer.compare(rect1.x, rect2.x);
        }

        return Integer.compare(rect1.y, rect2.y);
    }

    /**
     * 분석을 위해 BufferedImage를 임시 파일로 디스크에 저장
     */
    private File saveTemporaryImage(BufferedImage bufferedImage, int pageNumber) throws IOException {
        File temporaryFile = Files.createTempFile(
                PAGE_PREFIX + pageNumber + AppConstants.REPLACE_CHAR,
                AppConstants.EXT_PNG
        ).toFile();

        ImageIO.write(bufferedImage, AppConstants.MIME_PNG, temporaryFile);

        return temporaryFile;
    }

    /**
     * BufferedImage를 OpenCV 처리를 위한 Mat 객체(BGR 형식)로 변환
     */
    private Mat convertToMat(BufferedImage bufferedImage) {
        BufferedImage targetImage = bufferedImage;

        if (bufferedImage.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            targetImage = new BufferedImage(bufferedImage.getWidth(), bufferedImage.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            targetImage.getGraphics().drawImage(bufferedImage, 0, 0, null);
        }

        byte[] imagePixels = ((DataBufferByte) targetImage.getRaster().getDataBuffer()).getData();
        Mat imageMat = new Mat(targetImage.getHeight(), targetImage.getWidth(), CvType.CV_8UC3);
        imageMat.put(0, 0, imagePixels);

        return imageMat;
    }

    /**
     * 처리가 완료된 임시 파일 삭제
     */
    private void cleanupTemporaryFile(File temporaryFile) {
        if (temporaryFile != null && temporaryFile.exists()) {
            boolean isDeleted = temporaryFile.delete();

            if (!isDeleted) {
                temporaryFile.deleteOnExit();
            }
        }
    }

    /**
     * OCR 블록의 좌표를 OpenCV 연산용 행렬 리스트로 변환
     */
    private List<Mat> convertToMatList(List<TextBlock> textBlocks, MatResourceWrapper wrapper) {
        return textBlocks.stream().map(block -> {
            Mat matrix = wrapper.add(new Mat(AppConstants.Policy.QUAD_PTS, AppConstants.Policy.COORD_DIM, CvType.CV_32F));
            float[] coordinates = new float[8];

            for (int i = 0; i < 4; i++) {
                coordinates[i * 2] = block.getBoxPoint().get(i).getX();
                coordinates[i * 2 + 1] = block.getBoxPoint().get(i).getY();
            }

            matrix.put(0, 0, coordinates);

            return matrix;
        }).toList();
    }

    /**
     * 다각형 좌표 리스트의 기하학적 중심점 계산
     */
    private java.awt.Point calculateCenterPoint(List<Point> points) {
        int centerX = (int) points.stream().mapToInt(Point::getX).average().orElse(0);
        int centerY = (int) points.stream().mapToInt(Point::getY).average().orElse(0);

        return new java.awt.Point(centerX, centerY);
    }

    /**
     * OCR 좌표 데이터를 Java AWT Rectangle 객체로 변환
     */
    private Rectangle convertToAwtRectangle(TextBlock textBlock) {
        IntSummaryStatistics xStats = textBlock.getBoxPoint().stream().mapToInt(Point::getX).summaryStatistics();
        IntSummaryStatistics yStats = textBlock.getBoxPoint().stream().mapToInt(Point::getY).summaryStatistics();

        return new Rectangle(
                xStats.getMin(),
                yStats.getMin(),
                xStats.getMax() - xStats.getMin(),
                yStats.getMax() - yStats.getMin()
        );
    }

    /**
     * AWT Rectangle을 도메인 전용 사각형 객체로 변환
     */
    private PageData.Rect convertToDomainRect(Rectangle awtRectangle) {
        return new PageData.Rect(awtRectangle.x, awtRectangle.y, awtRectangle.width, awtRectangle.height);
    }
}
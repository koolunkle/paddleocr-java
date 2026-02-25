package com.example.ocr.service;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
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
 * 이미지 파싱, OCR 엔진 호출, 레이아웃 분석 및 최종 데이터 가공을 조율하는 통합 처리 서비스
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

    public AnalysisResponse process(File imageFile, List<Integer> targetPageNumbers, String documentName) throws IOException {
        List<AnalysisResponse.PageResult> pageResults = new ArrayList<>();
        run(imageFile, targetPageNumbers, documentName, pageResults::add);
        return new AnalysisResponse(documentName, pageResults);
    }

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

    private AnalysisResponse.PageResult processSinglePage(ImageReader imageReader, int imageIndex, int pageNumber, String documentName) {
        File temporaryImageFile = null;
        
        // MatResourceWrapper를 통해 생성된 OpenCV Mat 메모리 자동 관리
        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            BufferedImage bufferedImage = imageReader.read(imageIndex);
            temporaryImageFile = saveTemporaryImage(bufferedImage, pageNumber);
            
            // Mat 객체 생성과 동시에 wrapper에 안전하게 결속
            Mat imageMat = wrapper.add(convertToMat(bufferedImage));
            
            return executeAnalysis(imageMat, temporaryImageFile.getAbsolutePath(), pageNumber, documentName);
            
        } catch (Exception e) {
            throw new ProcessingException("Failed to process page: " + pageNumber, e);
        } finally {
            // Mat 객체는 wrapper가 이미 해제했으므로, 디스크 파일만 삭제
            cleanupTemporaryFile(temporaryImageFile);
        }
    }

    private AnalysisResponse.PageResult executeAnalysis(Mat imageMat, String imagePath, int pageNumber, String documentName) throws InterruptedException {
        long timeoutMs = this.appProperties.async().inferenceTimeoutMs();
        if (!this.concurrencyLimitSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new ServiceUnavailableException();
        }
        
        try {
            OcrResult ocrResult = this.inferenceEngine.runOcr(imagePath, this.paramConfig);
            List<LayoutService.LayoutRegion> layoutRegions = this.layoutService.detectRegions(imageMat, this.appProperties.engine().layoutScoreThreshold());
            
            this.visualService.saveLayoutDebug(imageMat, pageNumber, documentName, layoutRegions);
            
            AnalysisResponse.PageResult finalResult = buildPageResult(pageNumber, ocrResult, imageMat, documentName, layoutRegions);
            this.resultLogger.logPageResult(finalResult);
            
            return finalResult;
        } finally {
            this.concurrencyLimitSemaphore.release();
        }
    }

    private AnalysisResponse.PageResult buildPageResult(int pageNumber, OcrResult ocrResult, Mat imageMat, String documentName, List<LayoutService.LayoutRegion> layoutRegions) {
        if (ocrResult == null || ocrResult.getTextBlocks() == null) {
            return new AnalysisResponse.PageResult(pageNumber, AnalysisResponse.Type.RAW, new PageData.Raw(List.of(), "Empty OCR"));
        }

        List<TextBlock> textBlocks = ocrResult.getTextBlocks();
        
        // MatResourceWrapper를 통해 생성된 메모리 자동 관리
        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            List<Mat> boundingBoxMatrices = convertToMatList(textBlocks, wrapper);
            
            return Optional.ofNullable(this.decisionParser.parse(
                        textBlocks.stream().map(TextBlock::getText).toList(), 
                        boundingBoxMatrices
                    ))
                    .map(decisionData -> {
                        this.visualService.saveDecisionDebug(imageMat, pageNumber, documentName, decisionData.boundingBoxes());
                        return new AnalysisResponse.PageResult(pageNumber, AnalysisResponse.Type.DECISION, new PageData.Decision(decisionData.data()));
                    })
                    .orElseGet(() -> buildRawResult(pageNumber, textBlocks, layoutRegions));
        }
    }

    private AnalysisResponse.PageResult buildRawResult(int pageNumber, List<TextBlock> textBlocks, List<LayoutService.LayoutRegion> layoutRegions) {
        List<PageData.Region> parsedRegions = layoutRegions.stream().map(layoutRegion -> {
            List<TextBlock> innerBlocks = textBlocks.stream()
                    .filter(block -> layoutRegion.rect().contains(calculateCenterPoint(block.getBoxPoint())))
                    .toList();
            
            List<String> textLines = innerBlocks.stream()
                    .sorted(this::compareTextBlockPosition)
                    .map(block -> TextUtil.sanitize(block.getText()))
                    .toList();
            
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

    private int compareTextBlockPosition(TextBlock block1, TextBlock block2) {
        Rectangle rect1 = convertToAwtRectangle(block1);
        Rectangle rect2 = convertToAwtRectangle(block2);
        
        List<Integer> heights = List.of(rect1.height, rect2.height).stream().sorted().toList();
        int tolerance = heights.get(heights.size() / 2) / 2;
        
        if (Math.abs(rect1.y - rect2.y) <= tolerance) {
            return Integer.compare(rect1.x, rect2.x);
        }
        return Integer.compare(rect1.y, rect2.y);
    }

    private File saveTemporaryImage(BufferedImage bufferedImage, int pageNumber) throws IOException {
        File temporaryFile = Files.createTempFile(
                PAGE_PREFIX + pageNumber + AppConstants.REPLACE_CHAR, 
                AppConstants.EXT_PNG
        ).toFile();
        
        ImageIO.write(bufferedImage, AppConstants.MIME_PNG, temporaryFile);
        return temporaryFile;
    }

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

    private void cleanupTemporaryFile(File temporaryFile) {
        if (temporaryFile != null && temporaryFile.exists()) {
            boolean isDeleted = temporaryFile.delete();
            if (!isDeleted) {
                temporaryFile.deleteOnExit();
            }
        }
    }

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

    private java.awt.Point calculateCenterPoint(List<Point> points) {
        int centerX = (int) points.stream().mapToInt(Point::getX).average().orElse(0);
        int centerY = (int) points.stream().mapToInt(Point::getY).average().orElse(0);
        return new java.awt.Point(centerX, centerY);
    }

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

    private PageData.Rect convertToDomainRect(Rectangle awtRectangle) {
        return new PageData.Rect(awtRectangle.x, awtRectangle.y, awtRectangle.width, awtRectangle.height);
    }
}
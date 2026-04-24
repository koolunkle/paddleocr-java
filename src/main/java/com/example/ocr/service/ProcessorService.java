package com.example.ocr.service;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * OCR, 레이아웃 및 테이블 분석 그리고 데이터 가공을 조율하는 통합 처리 서비스
 * 이미지 파싱부터 최종 분석 결과 도출까지의 전체 파이프라인을 관리합니다.
 */
@Service
public class ProcessorService {

    // ========================================================================
    // 상수 및 로거
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(ProcessorService.class);
    
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
    private final TableService tableService;
    private final Semaphore concurrencyLimitSemaphore;

    // ========================================================================
    // 내부 데이터 구조
    // ========================================================================

    /** 수평 정렬 이상치 탐지를 위한 통계 분포 정보 (Left/Right Bound) */
    private record HorizontalDistribution(double lowerL, double upperL, double lowerR, double upperR) {}

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
            LayoutService layoutService,
            TableService tableService) {
        
        this.decisionParser = decisionParser;
        this.tableStructureEstimator = tableStructureEstimator;
        this.appProperties = appProperties;
        this.paramConfig = paramConfig;
        this.inferenceEngine = inferenceEngine;
        this.resultLogger = resultLogger;
        this.visualService = visualService;
        this.layoutService = layoutService;
        this.tableService = tableService;
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
            // 전체 페이지 OCR 실행
            OcrResult ocrResult = this.inferenceEngine.runOcr(imagePath, this.paramConfig);

            // 레이아웃 영역 탐지 실행
            List<LayoutService.LayoutRegion> layoutRegions = this.layoutService.detectRegions(imageMat, this.appProperties.engine().layoutScoreThreshold());
            
            // 인접한 동일 타입 영역(예: 법원명 + 결정) 병합
            List<LayoutService.LayoutRegion> mergedRegions = mergeNearbyRegions(layoutRegions);

            // 레이아웃 영역을 OCR 텍스트 라인에 맞춰 정밀 보정 
            List<LayoutService.LayoutRegion> adjustedRegions = adjustLayoutRegions(mergedRegions, ocrResult.getTextBlocks(), imageMat.width(), imageMat.height());
            this.visualService.saveLayoutDebug(imageMat, pageNumber, documentName, adjustedRegions);

            // 분석 결과 통합 및 가공
            AnalysisResponse.PageResult finalResult = buildPageResult(pageNumber, ocrResult, imageMat, documentName, adjustedRegions);

            this.resultLogger.logPageResult(finalResult);
            
            return finalResult;
        } finally {
            this.concurrencyLimitSemaphore.release();
        }
    }

    /**
     * SLANet 모델 결과를 바탕으로 표 구조를 분석하고 텍스트를 정밀 매핑합니다.
     */
    private PageData.TableStructure analyzeTable(Mat imageMat, Rectangle tableRect, List<TextBlock> allTextBlocks) {
        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            // 1. 모델 분석 실행
            Rect cropRect = new Rect(tableRect.x, tableRect.y, tableRect.width, tableRect.height);
            Mat tableMat = wrapper.add(new Mat(imageMat, cropRect));
            PageData.TableStructure structure = this.tableService.analyze(tableMat);

            if (structure == null) {
                return null;
            }

            // 2. 표 내부 텍스트 블록 수집
            List<TextBlock> tableBlocks = allTextBlocks.stream()
                    .filter(block -> tableRect.contains(calculateCenterPoint(block.getBoxPoint())))
                    .sorted(this::compareTextBlockPosition)
                    .toList();

            // 3. 셀-텍스트 정밀 매핑
            List<PageData.Cell> allCells = new ArrayList<>();
            if (structure.header() != null) {
                allCells.addAll(structure.header());
            }
            structure.rows().forEach(row -> allCells.addAll(row.cells()));

            Map<PageData.Cell, List<TextBlock>> cellToBlocks = new HashMap<>();
            for (TextBlock block : tableBlocks) {
                Rectangle blockRect = convertToAwtRectangle(block);
                double blockArea = (double) blockRect.width * blockRect.height;

                PageData.Cell bestCell = null;
                double bestIou = -1.0;
                double bestDistance = Double.MAX_VALUE;

                for (PageData.Cell cell : allCells) {
                    Rectangle absCellRect = new Rectangle(
                            tableRect.x + cell.rect().x(),
                            tableRect.y + cell.rect().y(),
                            cell.rect().width(),
                            cell.rect().height()
                    );

                    Rectangle intersection = absCellRect.intersection(blockRect);
                    double iou = 0.0;
                    if (intersection.width > 0 && intersection.height > 0) {
                        double intersectArea = intersection.width * intersection.height;
                        double cellArea = absCellRect.width * absCellRect.height;
                        iou = intersectArea / (blockArea + cellArea - intersectArea);
                    }

                    // 기하학적 일치도(IoU)와 중심점 간의 거리(L1 Distance)를 조합하여 텍스트 블록에 가장 적합한 셀을 탐색합니다.
                    double distance = Math.abs(absCellRect.getMinX() - blockRect.getMinX())
                            + Math.abs(absCellRect.getMinY() - blockRect.getMinY())
                            + Math.abs(absCellRect.getMaxX() - blockRect.getMaxX())
                            + Math.abs(absCellRect.getMaxY() - blockRect.getMaxY())
                            + Math.min(
                                    Math.abs(absCellRect.getMinX() - blockRect.getMinX())
                                            + Math.abs(absCellRect.getMinY() - blockRect.getMinY()),
                                    Math.abs(absCellRect.getMaxX() - blockRect.getMaxX())
                                            + Math.abs(absCellRect.getMaxY() - blockRect.getMaxY()));

                    // 높은 IoU를 우선하되, IoU가 동일할 경우 물리적 거리가 더 가까운 셀을 선택합니다.
                    if (iou > bestIou || (iou == bestIou && distance < bestDistance)) {
                        bestIou = iou;
                        bestDistance = distance;
                        bestCell = cell;
                    }
                }

                // 유의미한 겹침이 발생한 경우에만 해당 셀에 텍스트 블록을 할당합니다.
                if (bestCell != null && bestIou >= 1e-8) {
                    cellToBlocks.computeIfAbsent(bestCell, k -> new ArrayList<>()).add(block);
                }
            }

            // 4. 셀 텍스트 업데이트
            List<PageData.Cell> updatedHeader = null;
            if (structure.header() != null) {
                updatedHeader = structure.header().stream()
                        .map(cell -> buildUpdatedCell(cell, cellToBlocks.get(cell)))
                        .toList();
            }

            List<PageData.Row> updatedRows = structure.rows().stream()
                    .map(row -> new PageData.Row(row.cells().stream()
                            .map(cell -> buildUpdatedCell(cell, cellToBlocks.get(cell)))
                            .toList()))
                    .toList();

            return new PageData.TableStructure(updatedHeader, updatedRows);
        } catch (Exception e) {
            log.error("Table analysis failed", e);
            return null;
        }
    }

    private List<LayoutService.LayoutRegion> adjustLayoutRegions(List<LayoutService.LayoutRegion> regions, List<TextBlock> textBlocks, int imgWidth, int imgHeight) {
        if (textBlocks == null || textBlocks.isEmpty()) {
            return Collections.emptyList();
        }

        List<LayoutService.LayoutRegion> refinedRegions = new ArrayList<>();
        float layoutIouThreshold = this.appProperties.algorithm().layoutIouThreshold();
        
        for (LayoutService.LayoutRegion region : regions) {
            // 레이아웃 영역 내에 포함된 OCR 텍스트 블록 필터링
            // 유의미한 텍스트(최소 글자 수 및 신뢰도 이상)만 후보로 인정하여 노이즈 차단
            List<Rectangle> containedRects = textBlocks.stream()
                    .filter(this::isValidTextBlock)
                    .filter(block -> {
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

            // 영역 내 텍스트 블록 중 메인 텍스트 뭉치에서 너무 멀리 떨어진 고립 박스(인장 등) 제외
            List<Rectangle> mainTextRects = filterOutlierRects(containedRects);
            
            // 영역 내 모든 텍스트 블록을 포함하는 최소 외접 사각형(Union) 계산
            Rectangle ocrUnion = mainTextRects.stream().reduce(Rectangle::union).orElse(containedRects.get(0));
            Rectangle finalRect;

            if (LayoutType.fromCode(region.type()).isTable()) {
                // 표(Table)는 테두리선 보존을 위해 원본 탐지 박스와 OCR 박스의 합집합 사용 + 설정된 표 패딩 적용
                finalRect = region.rect().union(ocrUnion);
                finalRect = applyPadding(finalRect, this.appProperties.algorithm().tablePadding(), imgWidth, imgHeight);
            } else {
                // 일반 텍스트 영역은 실제 OCR 글자가 있는 위치로 최적화 + 설정된 텍스트 패딩 적용
                finalRect = applyPadding(ocrUnion, this.appProperties.algorithm().textPadding(), imgWidth, imgHeight);
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
            return new AnalysisResponse.PageResult(pageNumber, AnalysisResponse.Type.RAW, new PageData.Raw(List.of(), AppConstants.Policy.EMPTY_OCR_MSG));
        }

        List<TextBlock> validBlocks = ocrResult.getTextBlocks().stream()
                .filter(this::isValidTextBlock)
                .toList();

        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            List<Mat> boundingBoxMatrices = convertToMatList(validBlocks, wrapper);

            // 1. 특정 도메인 템플릿(법원 결정문 등) 파싱 시도
            return Optional.ofNullable(this.decisionParser.parse(
                            validBlocks.stream().map(TextBlock::getText).toList(),
                            boundingBoxMatrices
                    ))
                    .map(decisionData -> {
                        this.visualService.saveDecisionDebug(imageMat, pageNumber, documentName, decisionData.boundingBoxes());
                        return new AnalysisResponse.PageResult(pageNumber, AnalysisResponse.Type.DECISION, new PageData.Decision(decisionData.data()));
                    })
                    // 2. 특정 템플릿이 아니면 일반 분석(Raw) 결과로 반환
                    .orElseGet(() -> buildRawResult(pageNumber, validBlocks, layoutRegions, imageMat, documentName));
        }
    }

    /**
     * 일반적인 문서 구조를 가진 페이지의 분석 결과 생성 (표 구조 추정 포함)
     */
     private AnalysisResponse.PageResult buildRawResult(int pageNumber, List<TextBlock> textBlocks, List<LayoutService.LayoutRegion> layoutRegions, Mat imageMat, String documentName) {
        final int[] tableCounter = {0};

        List<PageData.Region> parsedRegions = layoutRegions.stream().map(layoutRegion -> {
            // 해당 영역 내에 중심점이 포함된 텍스트 블록 중 유효한 것만 필터링
            List<TextBlock> innerBlocks = textBlocks.stream()
                    .filter(block -> layoutRegion.rect().contains(calculateCenterPoint(block.getBoxPoint())))
                    .filter(this::isValidTextBlock)
                    .toList();

            // 텍스트를 상단->하단, 좌측->우측 순서로 정렬 및 정제
            List<String> textLines = innerBlocks.stream()
                    .sorted(this::compareTextBlockPosition)
                    .map(block -> TextUtil.sanitize(block.getText()))
                    .toList();

            // 표 영역인 경우 내부 셀 구조 추정
            PageData.TableStructure tableStructure = null;

            if (LayoutType.fromCode(layoutRegion.type()).isTable()) {
                // 표 구조 분석 수행 
                tableStructure = analyzeTable(imageMat, layoutRegion.rect(), textBlocks);

                // 표 구조 분석 결과가 아예 없는 경우, 폴백 적용
                if (tableStructure == null || (tableStructure.rows().isEmpty() && (tableStructure.header() == null || tableStructure.header().isEmpty()))) {
                    tableStructure = this.tableStructureEstimator.estimate(innerBlocks);
                } else {
                    this.visualService.saveTableHtml(pageNumber, ++tableCounter[0], documentName, tableStructure);
                }
            }

            return new PageData.Region(layoutRegion.type(), layoutRegion.score(), convertToDomainRect(layoutRegion.rect()), textLines, tableStructure);
        }).toList();

        return new AnalysisResponse.PageResult(pageNumber, AnalysisResponse.Type.RAW, new PageData.Raw(parsedRegions, null));
    }

    /**
     * 셀 영역에 매핑된 텍스트 블록들을 결합하여 셀 데이터를 업데이트합니다.
     */
    private PageData.Cell buildUpdatedCell(PageData.Cell cell, List<TextBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return new PageData.Cell("", cell.row(), cell.col(), cell.colspan(), cell.rowspan(), cell.rect());
        }
        String text = blocks.stream()
                .sorted(this::compareTextBlockPosition)
                .map(block -> TextUtil.sanitize(block.getText()))
                .collect(Collectors.joining(" ")).trim();
        return new PageData.Cell(text, cell.row(), cell.col(), cell.colspan(), cell.rowspan(), cell.rect());
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
     * OCR 텍스트 블록의 품질이 유효한지 검증 (노이즈 제거용)
     */
    private boolean isValidTextBlock(TextBlock block) {
        if(block == null) {
            return false;
        }

        String text = block.getText();
        if(text == null || text.isBlank()) {
            return false;
        }

        long validCharCount = text.chars().filter(ch -> !Character.isWhitespace(ch)).count();

        // 설정된 최소 글자 수 미만은 노이즈로 간주 
        if(validCharCount < this.appProperties.algorithm().minTextLength()) {
            return false;
        }

        float[] scores = block.getCharScores();
        
        // 인식 품질(CharScore)이 기준치 미만이면 노이즈로 간주 
        if(scores == null || scores.length == 0) {
            return false;
        }

        double averageScore = IntStream.range(0, scores.length)
                .mapToDouble(i -> scores[i])
                .average()
                .orElse(0.0);

        return averageScore >= this.appProperties.algorithm().minCharScore();
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

    /**
     * 텍스트 블록 목록 중 수평 분포에서 벗어난 고립된 블록(인장 등)을 필터링
     * 문장의 마지막 짧은 라인이 누락되지 않도록 수직 인접성 및 정렬 기준을 고려
     */
    private List<Rectangle> filterOutlierRects(List<Rectangle> rects) {
        if (rects.size() < AppConstants.Policy.MIN_OUTLIER_SAMPLE) {
            return rects;
        }

        // 수평 분포 정보(IQR 등)를 한 번만 계산하여 성능 최적화 (O(N))
        HorizontalDistribution distribution = calculateHorizontalDistribution(rects);
        
        // 수직 위치(Y) 기준으로 정렬하여 문맥 파악
        List<Rectangle> sortedByY = rects.stream()
                .sorted(Comparator.comparingInt(r -> r.y))
                .toList();

        List<Rectangle> filtered = new ArrayList<>();
        filtered.add(sortedByY.get(0));

        for (int i = 1; i < sortedByY.size(); i++) {
            Rectangle current = sortedByY.get(i);
            Rectangle previous = sortedByY.get(i - 1);

            // 1. 수직적으로 바로 윗줄과 가깝다면(문장/문단의 일부) 무조건 보호
            if (isVerticallyAdjacent(previous, current)) {
                filtered.add(current);
                continue;
            }

            // 2. 수직으로 떨어져 있다면 수평 정렬 상태를 체크하여 고립된 노이즈인지 판별
            if (isAlignedWithMainText(current, distribution)) {
                filtered.add(current);
            }
        }

        return filtered;
    }

    /** 
     * 텍스트 블록들의 수평 분포(IQR)를 분석하여 이상치 탐지를 위한 기준점 계산
     */
    private HorizontalDistribution calculateHorizontalDistribution(List<Rectangle> rects) {
        List<Integer> lefts = rects.stream().map(r -> r.x).sorted().toList();
        List<Integer> rights = rects.stream().map(r -> r.x + r.width).sorted().toList();

        return new HorizontalDistribution(
                calculateBound(lefts, true), calculateBound(lefts, false),
                calculateBound(rights, true), calculateBound(rights, false)
        );
    }

    /**
     *  사분위수(IQR)를 활용하여 상/하단 통계적 임계 경계값 계산
     */
    private double calculateBound(List<Integer> values, boolean isLower) {
        int q1 = values.get(values.size() / 4);
        int q3 = values.get(values.size() * 3 / 4);
        double iqr = q3 - q1;
        return isLower ? q1 - AppConstants.Policy.IQR_FACTOR * iqr : q3 + AppConstants.Policy.IQR_FACTOR * iqr;
    }

    /** 
     * 특정 사각형이 본문 텍스트의 수평 정렬 범위(왼쪽/오른쪽) 내에 있는지 확인
     */
    private boolean isAlignedWithMainText(Rectangle target, HorizontalDistribution dist) {
        boolean leftAligned = target.x >= dist.lowerL() && target.x <= dist.upperL();
        boolean rightAligned = (target.x + target.width) >= dist.lowerR() && (target.x + target.width) <= dist.upperR();
        return leftAligned || rightAligned;
    }

    /** 
     * 두 사각형이 수직적으로 매우 인접하여 같은 문장/문단의 일부인지 판단
     */
    private boolean isVerticallyAdjacent(Rectangle prev, Rectangle curr) {
        return (curr.y - (prev.y + prev.height)) <= prev.height * AppConstants.Policy.ADJACENCY_MULTIPLIER;
    }

    /**
     * 수직으로 인접하고 수평으로 겹치는 동일 타입의 레이아웃 영역들을 하나로 병합
     */
    private List<LayoutService.LayoutRegion> mergeNearbyRegions(List<LayoutService.LayoutRegion> regions) {
        if (regions.size() < 2) {
            return regions;
        }

        // Y축 상단 좌표 기준으로 정렬
        List<LayoutService.LayoutRegion> sortedRegions = regions.stream()
                .sorted(Comparator.comparingInt(r -> r.rect().y))
                .toList();

        List<LayoutService.LayoutRegion> merged = new ArrayList<>();
        LayoutService.LayoutRegion current = sortedRegions.get(0);

        for (int i = 1; i < sortedRegions.size(); i++) {
            LayoutService.LayoutRegion next = sortedRegions.get(i);
            
            // 동일 타입이고 수직 간격이 좁으며 수평적으로 겹치는 경우 병합
            if (current.type().equals(next.type()) && isVerticallyClose(current.rect(), next.rect())) {
                current = new LayoutService.LayoutRegion(
                        current.type(), 
                        current.rect().union(next.rect()), 
                        Math.max(current.score(), next.score())
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /** 
     * 두 레이아웃 영역이 병합 가능한 수준으로 수직 인접 및 수평 중첩되는지 확인
     */
    private boolean isVerticallyClose(Rectangle r1, Rectangle r2) {
        int gap = r2.y - (r1.y + r1.height);
        int threshold = (int) (r1.height * AppConstants.Policy.ADJACENCY_MULTIPLIER);
        boolean xOverlaps = Math.max(r1.x, r2.x) < Math.min(r1.x + r1.width, r2.x + r2.width);
        
        return gap >= AppConstants.Policy.VERTICAL_GAP_TOLERANCE && gap <= threshold && xOverlaps;
    }
}
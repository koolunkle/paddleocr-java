package com.example.ocr.service;

import java.awt.Rectangle;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ocr.config.AppProperties;
import com.example.ocr.support.MatResourceWrapper;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;

/**
 * ONNX Runtime 기반 레이아웃 분석 서비스
 * 문서 내 텍스트, 표, 그림 등의 영역을 딥러닝 모델로 탐지합니다.
 */
@Service
public class LayoutService {

    // ========================================================================
    // 상수 및 로거
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(LayoutService.class);

    // [모델 아키텍처 상수]
    private static final int[] FEATURE_MAP_STRIDES = {8, 16, 32, 64};
    private static final int DFL_DIMENSION = 32;
    private static final int DFL_CHANNELS = 8;
    private static final float ANCHOR_CENTER_OFFSET = 0.5f;

    // [추론 설정 상수]
    private static final int BATCH_SIZE = 1;
    private static final int RGB_CHANNELS = 3;
    private static final float MIN_IOU_AREA = 0.0f;
    private static final double PIXEL_NORMALIZATION_FACTOR = 1.0 / 255.0;

    // ========================================================================
    // 상태 변수
    // ========================================================================

    private final AppProperties appProperties;
    private final OrtEnvironment onnxEnvironment;
    private OrtSession onnxSession;
    private List<String> classLabels;

    public record LayoutRegion(String type, Rectangle rect, float score) {}

    // ========================================================================
    // 생성자 및 생명주기 관리 
    // ========================================================================

    public LayoutService(AppProperties appProperties, OrtEnvironment onnxEnvironment) {
        this.appProperties = appProperties;
        this.onnxEnvironment = onnxEnvironment;
    }

    /**
     * 모델 로드 및 탐지 클래스 레이블 초기화
     */
    public void init(String modelPath, List<String> dictionaryLabels) throws OrtException {
        if (this.onnxSession != null) {
            return;
        }

        try (OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions()) {
            sessionOptions.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            this.onnxSession = this.onnxEnvironment.createSession(modelPath, sessionOptions);
        }

        this.classLabels = Optional.ofNullable(dictionaryLabels)
                .orElse(Collections.emptyList())
                .stream()
                .map(String::trim)
                .toList();
                
        log.info("[LayoutService] Layout Model READY (Loaded Labels: {})", this.classLabels.size());
    }

    @PreDestroy
    public void close() {
        if (this.onnxSession != null) {
            try {
                this.onnxSession.close();
            } catch (Exception e) {
                log.warn("[LayoutService] Failed to close layout session", e);
            }
        }
    }

    // ========================================================================
    // 공개 메서드 - 추론 파이프라인
    // ========================================================================

    /**
     * 이미지에서 레이아웃 영역을 탐지 (메인 파이프라인)
     */
    public List<LayoutRegion> detectRegions(Mat sourceImage, float scoreThreshold) {
        if (this.onnxSession == null || sourceImage == null || sourceImage.empty()) {
            return Collections.emptyList();
        }

        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            int targetWidth = this.appProperties.models().layoutTargetWidth();
            int targetHeight = this.appProperties.models().layoutTargetHeight();

            // 입력 이미지 비율에 맞춰 스케일 계산 (Stretch Resize 대응)
            float scaleX = (float) targetWidth / sourceImage.width();
            float scaleY = (float) targetHeight / sourceImage.height();

            // 1. 전처리 (Preprocessing)
            float[] inputTensorData = prepareInputTensor(sourceImage, targetWidth, targetHeight, wrapper);

            // 2. 추론 및 후처리 (Inference & Post-processing)
            return runPredictionAndPostProcess(
                    inputTensorData,
                    sourceImage.width(),
                    sourceImage.height(),
                    scaleX,
                    scaleY,
                    scoreThreshold,
                    targetWidth,
                    targetHeight
            );

        } catch (Exception e) {
            log.error("[LayoutService] Layout detection failed", e);
            return Collections.emptyList();
        } 
    }

    // ========================================================================
    // 비공개 메서드 - 전처리 (Preprocessing)
    // ========================================================================

    /**
     * 입력 텐서 준비: 리사이즈, 정규화 및 평탄화(HWC -> CHW) 수행
     */
    private float[] prepareInputTensor(Mat sourceImage, int targetWidth, int targetHeight, MatResourceWrapper wrapper) {
        // 1. Stretch 리사이즈 
        Mat resizedImage = wrapper.add(new Mat());
        Imgproc.resize(sourceImage, resizedImage, new Size(targetWidth, targetHeight));

        // 2. 픽셀값 정규화 (1/255.0) 및 ImageNet 통계 적용
        Mat normalizedImage = wrapper.add(new Mat());
        resizedImage.convertTo(normalizedImage, CvType.CV_32FC3, PIXEL_NORMALIZATION_FACTOR);

        applyImageNetNormalization(normalizedImage);

        // 3. 텐서 구조 변환 (HWC -> CHW)
        return convertHwcToChwPlanar(normalizedImage, targetWidth, targetHeight);
    }

    private void applyImageNetNormalization(Mat canvas) {
        List<Float> meanValues = this.appProperties.models().imagenetMean();
        List<Float> stdValues = this.appProperties.models().imagenetStd();
        
        Core.subtract(canvas, new Scalar(meanValues.get(0), meanValues.get(1), meanValues.get(2)), canvas);
        Core.divide(canvas, new Scalar(stdValues.get(0), stdValues.get(1), stdValues.get(2)), canvas);
    }

    private float[] convertHwcToChwPlanar(Mat canvas, int targetWidth, int targetHeight) {
        int totalPixels = targetWidth * targetHeight;
        float[] interleavedPixels = new float[totalPixels * RGB_CHANNELS];
        canvas.get(0, 0, interleavedPixels);
        
        float[] planarPixels = new float[RGB_CHANNELS * totalPixels];
        
        for (int i = 0; i < totalPixels; i++) {
            planarPixels[i]                  = interleavedPixels[i * 3];       // Red
            planarPixels[i + totalPixels]    = interleavedPixels[i * 3 + 1];   // Green
            planarPixels[i + totalPixels * 2]= interleavedPixels[i * 3 + 2];   // Blue
        }
        
        return planarPixels;
    }

    // ========================================================================
    // 비공개 메서드 - 추론 및 텐서 추출 (Inference)
    // ========================================================================

    private List<LayoutRegion> runPredictionAndPostProcess(
            float[] inputTensorData,
            int originalWidth,
            int originalHeight,
            float scaleX,
            float scaleY,
            float scoreThreshold,
            int targetWidth,
            int targetHeight) throws OrtException {
                
        long[] inputShape = {BATCH_SIZE, RGB_CHANNELS, targetHeight, targetWidth};
        String inputName = this.onnxSession.getInputNames().iterator().next();

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(this.onnxEnvironment, FloatBuffer.wrap(inputTensorData), inputShape);
             OrtSession.Result inferenceResult = this.onnxSession.run(Map.of(inputName, inputTensor))) {

            Map<Integer, float[]> classScoreMap = new HashMap<>();
            Map<Integer, float[]> boundingBoxMap = new HashMap<>();
            Map<Integer, Integer> channelDimensionMap = new HashMap<>();

            for (Map.Entry<String, OnnxValue> entry : inferenceResult) {
                OnnxTensor outputTensor = (OnnxTensor) entry.getValue();
                long[] shape = outputTensor.getInfo().getShape();
                log.debug("[LayoutService] Output Node: {}, Shape: {}", entry.getKey(), Arrays.asList(shape));

                // 1. NMS가 모델 내부에 포함된 경우
                if ((shape.length == 3 && shape[2] == 6) || (shape.length == 2 && shape[1] == 6)) {
                    int numDets = (int) (shape.length == 3 ? shape[1] : shape[0]);
                    float[] tensorValues = new float[numDets * 6];
                    outputTensor.getFloatBuffer().get(tensorValues);

                    return processPostProcessedOutput(tensorValues, numDets, originalWidth, originalHeight, scaleX, scaleY, scoreThreshold);
                }

                // 2. Raw PicoDet 출력 (멀티스케일 피처 맵)
                if (shape.length >= 3) {
                    int anchorCount = (int) shape[1];
                    int featureDimension = (int) shape[2];
                    float[] tensorValues = new float[anchorCount * featureDimension];
                    outputTensor.getFloatBuffer().get(tensorValues);

                    if (featureDimension == DFL_DIMENSION) {
                        boundingBoxMap.put(anchorCount, tensorValues);
                    } else {
                        classScoreMap.put(anchorCount, tensorValues);
                        channelDimensionMap.put(anchorCount, featureDimension);
                    }
                }
            }
            
            return processModelOutputs(
                    classScoreMap, boundingBoxMap, channelDimensionMap,
                    originalWidth, originalHeight, scaleX, scaleY,
                    scoreThreshold, targetWidth, targetHeight
            );
        }
    }

    // ========================================================================
    // 비공개 메서드 - 후처리 (Post-processing & NMS)
    // ========================================================================
    private List<LayoutRegion> processPostProcessedOutput(
            float[] data, int numDets, int originalWidth, int originalHeight,
            float scaleX, float scaleY, float scoreThreshold) {

        List<LayoutRegion> candidateRegions = new ArrayList<>();
        float expansionMargin = this.appProperties.models().layoutExpansionMargin();

        for (int i = 0; i < numDets; i++) {
            int offset = i * 6;
            float x1 = data[offset];
            float y1 = data[offset + 1];
            float x2 = data[offset + 2];
            float y2 = data[offset + 3];
            float score = data[offset + 4];
            int classIdx = (int) data[offset + 5];

            if (score < scoreThreshold || classIdx < 0) {
                continue;
            }

            // 모델 좌표를 원본 이미지 해상도로 복원
            double realX1 = Math.max(0, x1 / scaleX);
            double realY1 = Math.max(0, y1 / scaleY);
            double realX2 = Math.min(originalWidth, x2 / scaleX);
            double realY2 = Math.min(originalHeight, y2 / scaleY);

            Rectangle boundingBox = buildPaddedRectangle(realX1, realY1, realX2, realY2, expansionMargin, originalWidth, originalHeight);
            String labelName = (classIdx < this.classLabels.size()) ? this.classLabels.get(classIdx) : String.valueOf(classIdx);

            candidateRegions.add(new LayoutRegion(labelName, boundingBox, score));
        }

        return applyNonMaximumSuppression(candidateRegions, this.appProperties.models().layoutNmsThreshold());
    }

    private List<LayoutRegion> processModelOutputs(
            Map<Integer, float[]> classScoreMap,
            Map<Integer, float[]> boundingBoxMap,
            Map<Integer, Integer> channelDimensionMap,
            int originalWidth,
            int originalHeight,
            float scaleX,
            float scaleY,
            float scoreThreshold,
            int targetWidth,
            int targetHeight) {

        List<LayoutRegion> allCandidates = new ArrayList<>();
        float expansionMargin = this.appProperties.models().layoutExpansionMargin();

        for (int stride : FEATURE_MAP_STRIDES) {
            int gridHeight = (int) Math.ceil((double) targetHeight / stride);
            int gridWidth = (int) Math.ceil((double) targetWidth / stride);
            int totalAnchors = gridHeight * gridWidth;

            if (!classScoreMap.containsKey(totalAnchors) || !boundingBoxMap.containsKey(totalAnchors)) {
                continue;
            }

            float[] anchorScores = classScoreMap.get(totalAnchors);
            float[] anchorBoxes = boundingBoxMap.get(totalAnchors);
            int numClasses = channelDimensionMap.get(totalAnchors);

            for (int anchorIndex = 0; anchorIndex < totalAnchors; anchorIndex++) {
                decodeSingleAnchor(
                        anchorIndex, numClasses, anchorScores, scoreThreshold,
                        gridWidth, stride, anchorBoxes, scaleX, scaleY,
                        originalWidth, originalHeight, expansionMargin, allCandidates
                );
            }
        }

        // 상위 1000개 후보군 유지 후 NMS 적용
        allCandidates.sort(Comparator.comparingDouble(LayoutRegion::score).reversed());
        List<LayoutRegion> topCandidates = allCandidates.stream().limit(1000).toList();

        return applyNonMaximumSuppression(topCandidates, this.appProperties.models().layoutNmsThreshold());
    }

    private void decodeSingleAnchor(
            int anchorIndex, int numClasses, float[] anchorScores, float scoreThreshold,
            int gridWidth, int stride, float[] anchorBoxes, float scaleX, float scaleY,
            int originalWidth, int originalHeight, float expansionMargin,
            List<LayoutRegion> candidateRegions) {
                
        int bestClassIndex = -1;
        float maxScore = -Float.MAX_VALUE;

        for (int classIdx = 0; classIdx < numClasses; classIdx++) {
            float score = anchorScores[anchorIndex * numClasses + classIdx];
            if (score > maxScore) {
                maxScore = score;
                bestClassIndex = classIdx;
            }
        }

        float finalScore = maxScore;
        if (finalScore < scoreThreshold) {
            return;
        }

        float gridX = anchorIndex % gridWidth;
        float gridY = anchorIndex / gridWidth;
        float centerX = (gridX + ANCHOR_CENTER_OFFSET) * stride;
        float centerY = (gridY + ANCHOR_CENTER_OFFSET) * stride;

        // Distribution Focal Loss 복호화
        float[] boxDistances = decodeDistributionFocalLoss(anchorBoxes, anchorIndex, stride);

        // 중심점 기준으로 경계면 좌표 산출 및 스케일 복원
        double x1 = Math.max(0, (centerX - boxDistances[0]) / scaleX);
        double y1 = Math.max(0, (centerY - boxDistances[1]) / scaleY);
        double x2 = Math.min(originalWidth, (centerX + boxDistances[2]) / scaleX);
        double y2 = Math.min(originalHeight, (centerY + boxDistances[3]) / scaleY);

        Rectangle boundingBox = buildPaddedRectangle(x1, y1, x2, y2, expansionMargin, originalWidth, originalHeight);
        
        if (boundingBox.width > 0 && boundingBox.height > 0) {
            String labelName = (bestClassIndex < this.classLabels.size())
                    ? this.classLabels.get(bestClassIndex)
                    : String.valueOf(bestClassIndex);

            candidateRegions.add(new LayoutRegion(labelName, boundingBox, finalScore));
        }
    }

    private Rectangle buildPaddedRectangle(double x1, double y1, double x2, double y2, float expansionMargin, int originalWidth, int originalHeight) {
        double width = x2 - x1;
        double height = y2 - y1;
        
        int paddedX1 = (int) Math.max(0, x1 - (width * expansionMargin));
        int paddedY1 = (int) Math.max(0, y1 - (height * expansionMargin));
        int paddedX2 = (int) Math.min(originalWidth, x2 + (width * expansionMargin));
        int paddedY2 = (int) Math.min(originalHeight, y2 + (height * expansionMargin));
        
        return new Rectangle(paddedX1, paddedY1, paddedX2 - paddedX1, paddedY2 - paddedY1);
    }

    private float[] decodeDistributionFocalLoss(float[] boundingBoxes, int anchorIndex, int stride) {
        float[] distances = new float[4];
        int offset = anchorIndex * DFL_DIMENSION;
        
        for (int edgeIndex = 0; edgeIndex < 4; edgeIndex++) {
            float sumExp = 0;
            float expectedValue = 0;
            float maxLogit = -Float.MAX_VALUE;
            
            int startIdx = offset + (edgeIndex * DFL_CHANNELS);
            
            for (int j = 0; j < DFL_CHANNELS; j++) {
                maxLogit = Math.max(maxLogit, boundingBoxes[startIdx + j]);
            }
            
            for (int j = 0; j < DFL_CHANNELS; j++) {
                float expVal = (float) Math.exp(boundingBoxes[startIdx + j] - maxLogit);
                sumExp += expVal;
                expectedValue += expVal * j;
            }
            
            distances[edgeIndex] = (expectedValue / sumExp) * stride;
        }
        return distances;
    }

    private List<LayoutRegion> applyNonMaximumSuppression(List<LayoutRegion> regions, float iouThreshold) {
        if (regions.isEmpty()) {
            return regions;
        }

        // 클래스별로 그룹화하여 독립적으로 NMS 수행
        Map<String, List<LayoutRegion>> regionsByClass = new HashMap<>();
        for (LayoutRegion region : regions) {
            regionsByClass.computeIfAbsent(region.type(), k -> new ArrayList<>()).add(region);
        }

        List<LayoutRegion> finalRegions = new ArrayList<>();

        for (List<LayoutRegion> classRegions : regionsByClass.values()) {
            classRegions.sort(Comparator.comparingDouble(LayoutRegion::score).reversed());

            boolean[] isSuppressed = new boolean[classRegions.size()];
            for (int i = 0; i < classRegions.size(); i++) {
                if (isSuppressed[i]) {
                    continue;
                }

                LayoutRegion current = classRegions.get(i);
                finalRegions.add(current);

                for (int j = i + 1; j < classRegions.size(); j++) {
                    if (!isSuppressed[j] && calculateIntersectionOverUnion(current.rect(), classRegions.get(j).rect()) > iouThreshold) {
                        isSuppressed[j] = true;
                    }
                }
            }
        }

        finalRegions.sort(Comparator.comparingDouble(LayoutRegion::score).reversed());
        return finalRegions;
    }

    private float calculateIntersectionOverUnion(Rectangle rect1, Rectangle rect2) {
        int intersectX1 = Math.max(rect1.x, rect2.x);
        int intersectY1 = Math.max(rect1.y, rect2.y);
        int intersectX2 = Math.min(rect1.x + rect1.width, rect2.x + rect2.width);
        int intersectY2 = Math.min(rect1.y + rect1.height, rect2.y + rect2.height);
        
        if (intersectX1 >= intersectX2 || intersectY1 >= intersectY2) {
            return MIN_IOU_AREA;
        }
        
        float intersectionArea = (float) (intersectX2 - intersectX1) * (intersectY2 - intersectY1);
        float unionArea = (float) (rect1.width * rect1.height + rect2.width * rect2.height) - intersectionArea;
        
        return unionArea <= MIN_IOU_AREA ? MIN_IOU_AREA : intersectionArea / unionArea;
    }
}
package com.example.ocr.service;

import java.awt.Rectangle;
import java.nio.FloatBuffer;
import java.util.ArrayList;
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
 * ONNX Runtime을 이용하여 이미지의 레이아웃(텍스트, 표, 그림 등) 영역을
 * 딥러닝 모델로 탐지하고 분석하는 서비스
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

    public List<LayoutRegion> detectRegions(Mat sourceImage, float scoreThreshold) {
        if (this.onnxSession == null || sourceImage == null || sourceImage.empty()) {
            return Collections.emptyList();
        }

        // MatResourceWrapper를 통해 생성된 메모리 자동 관리
        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            int targetWidth = this.appProperties.models().layoutTargetWidth();
            int targetHeight = this.appProperties.models().layoutTargetHeight();
            
            double resizeScale = Math.min(
                    (double) targetWidth / sourceImage.width(), 
                    (double) targetHeight / sourceImage.height()
            );

            if (resizeScale <= 0) {
                return Collections.emptyList();
            }

            // 1. 전처리 (Preprocessing)
            float[] inputTensorData = prepareInputTensor(sourceImage, targetWidth, targetHeight, resizeScale, wrapper);
            
            // 2. 추론 및 후처리 (Inference & Post-processing)
            return runPredictionAndPostProcess(
                    inputTensorData, 
                    sourceImage.width(), 
                    sourceImage.height(), 
                    resizeScale, 
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

    private float[] prepareInputTensor(Mat sourceImage, int targetWidth, int targetHeight, double resizeScale, MatResourceWrapper wrapper) {
        int scaledWidth = (int) Math.round(sourceImage.width() * resizeScale);
        int scaledHeight = (int) Math.round(sourceImage.height() * resizeScale);

        // 1. 리사이즈 생성
        Mat resizedImage = wrapper.add(new Mat());
        Imgproc.resize(sourceImage, resizedImage, new Size(scaledWidth, scaledHeight));

        // 2. 패딩용 캔버스 생성 
        Mat paddedCanvas = wrapper.add(new Mat(new Size(targetWidth, targetHeight), CvType.CV_32FC3, new Scalar(0, 0, 0)));
        
        // 3. 픽셀값 정규화 생성
        Mat normalizedImage = wrapper.add(new Mat());
        resizedImage.convertTo(normalizedImage, CvType.CV_32FC3, PIXEL_NORMALIZATION_FACTOR);

        // 4. 리사이즈된 이미지를 캔버스의 좌측 상단(ROI)에 복사
        Mat regionOfInterest = wrapper.add(paddedCanvas.submat(0, scaledHeight, 0, scaledWidth));
        normalizedImage.copyTo(regionOfInterest);

        // 5. ImageNet 평균 및 표준편차를 적용하여 정규화
        applyImageNetNormalization(paddedCanvas);
        
        // 6. HWC -> CHW 평탄화 변환
        return convertHwcToChwPlanar(paddedCanvas, targetWidth, targetHeight);
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
            double resizeScale, 
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
            
            return processModelOutputs(
                    classScoreMap, boundingBoxMap, channelDimensionMap, 
                    originalWidth, originalHeight, resizeScale, 
                    scoreThreshold, targetWidth, targetHeight
            );
        }
    }

    // ========================================================================
    // 비공개 메서드 - 후처리 (Post-processing & NMS)
    // ========================================================================

    private List<LayoutRegion> processModelOutputs(
            Map<Integer, float[]> classScoreMap, 
            Map<Integer, float[]> boundingBoxMap,
            Map<Integer, Integer> channelDimensionMap, 
            int originalWidth, 
            int originalHeight, 
            double resizeScale, 
            float scoreThreshold, 
            int targetWidth, 
            int targetHeight) {
                
        List<LayoutRegion> candidateRegions = new ArrayList<>();
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
                        gridWidth, stride, anchorBoxes, resizeScale, 
                        originalWidth, originalHeight, expansionMargin, candidateRegions
                );
            }
        }
        
        return applyNonMaximumSuppression(candidateRegions, this.appProperties.models().layoutNmsThreshold());
    }

    private void decodeSingleAnchor(
            int anchorIndex, int numClasses, float[] anchorScores, float scoreThreshold, 
            int gridWidth, int stride, float[] anchorBoxes, double resizeScale, 
            int originalWidth, int originalHeight, float expansionMargin, 
            List<LayoutRegion> candidateRegions) {
                
        int bestClassIndex = -1;
        float maxScore = -1.0f;
        
        for (int classIdx = 0; classIdx < numClasses; classIdx++) {
            float score = anchorScores[anchorIndex * numClasses + classIdx];
            if (score > maxScore) {
                maxScore = score;
                bestClassIndex = classIdx;
            }
        }
        
        if (maxScore < scoreThreshold) {
            return;
        }

        float gridX = anchorIndex % gridWidth;
        float gridY = anchorIndex / gridWidth;
        float centerX = (gridX + ANCHOR_CENTER_OFFSET) * stride;
        float centerY = (gridY + ANCHOR_CENTER_OFFSET) * stride;
        
        float[] boxDistances = decodeDistributionFocalLoss(anchorBoxes, anchorIndex, stride);

        double x1 = Math.max(0, (centerX - boxDistances[0]) / resizeScale);
        double y1 = Math.max(0, (centerY - boxDistances[1]) / resizeScale);
        double x2 = Math.min(originalWidth, (centerX + boxDistances[2]) / resizeScale);
        double y2 = Math.min(originalHeight, (centerY + boxDistances[3]) / resizeScale);

        Rectangle boundingBox = buildPaddedRectangle(x1, y1, x2, y2, expansionMargin, originalWidth, originalHeight);
        
        if (boundingBox.width > 0 && boundingBox.height > 0) {
            String labelName = (bestClassIndex < this.classLabels.size()) 
                    ? this.classLabels.get(bestClassIndex) 
                    : String.valueOf(bestClassIndex);
            candidateRegions.add(new LayoutRegion(labelName, boundingBox, maxScore));
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
        
        regions.sort(Comparator.comparingDouble(LayoutRegion::score).reversed());
        
        List<LayoutRegion> finalRegions = new ArrayList<>();
        boolean[] isSuppressed = new boolean[regions.size()];
        
        for (int i = 0; i < regions.size(); i++) {
            if (isSuppressed[i]) {
                continue;
            }
            
            LayoutRegion currentRegion = regions.get(i);
            finalRegions.add(currentRegion);
            
            for (int j = i + 1; j < regions.size(); j++) {
                if (!isSuppressed[j] && calculateIntersectionOverUnion(currentRegion.rect(), regions.get(j).rect()) > iouThreshold) {
                    isSuppressed[j] = true;
                }
            }
        }
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
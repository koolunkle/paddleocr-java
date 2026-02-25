package com.example.ocr.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.opencv.core.Mat;
import org.opencv.core.Rect;

import com.example.ocr.constant.AppConstants;

/**
 * OCR 결과로 얻은 공간 좌표(Bounding Box)를 분석하고,
 * Y축을 기준으로 같은 줄(Line)에 있는 텍스트를 판별하여 하나로 병합하는 유틸리티 클래스입니다.
 */
public final class BoxUtil {

    // ========================================================================
    // 내부 데이터 구조 
    // ========================================================================

    /**
     * 개별 텍스트 블록 또는 병합된 텍스트 라인의 공간 정보를 담는 불변 레코드입니다.
     *
     * @param text    텍스트 내용
     * @param rect    텍스트가 차지하는 사각형 영역 (Bounding Box)
     * @param centerY 텍스트 영역의 Y축 중심 좌표 (같은 줄인지 판별하는 기준)
     */
    public record TextLine(String text, Rect rect, double centerY) {}

    // ========================================================================
    // 생성자 차단
    // ========================================================================

    private BoxUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ========================================================================
    // 공개 메서드
    // ========================================================================

    /**
     * 텍스트와 좌표 목록을 바탕으로, Y축 중심점이 유사한 텍스트끼리 같은 줄로 묶어 병합합니다.
     *
     * @param textList       텍스트 문자열 목록
     * @param boxMatrices    각 텍스트의 다각형 영역 좌표(OpenCV Mat) 목록
     * @param yAxisTolerance 같은 줄로 인정할 Y축 중심점의 최대 오차 (픽셀 단위)
     * @return 줄(Line) 단위로 병합이 완료된 텍스트 객체(TextLine) 목록
     */
    public static List<TextLine> groupByLines(List<String> textList, List<Mat> boxMatrices, int yAxisTolerance) {
        if (textList == null || boxMatrices == null || textList.size() != boxMatrices.size()) {
            return List.of();
        }

        // 1. 전체 텍스트를 TextLine 객체로 변환하고 Y축 중심점(위에서 아래)을 기준으로 정렬
        List<TextLine> allTextLines = IntStream.range(0, textList.size())
                .mapToObj(index -> createTextLineFromMat(textList.get(index), boxMatrices.get(index)))
                .sorted(Comparator.comparingDouble(TextLine::centerY))
                .toList();

        if (allTextLines.isEmpty()) {
            return new ArrayList<>();
        }

        List<TextLine> mergedResult = new ArrayList<>();
        List<TextLine> currentLineGroup = new ArrayList<>();
        
        // 첫 번째 요소를 기준 그룹에 추가
        currentLineGroup.add(allTextLines.get(0));

        // 2. 순차적으로 순회하며 허용 오차(Tolerance) 내에 있으면 같은 그룹, 벗어나면 분리
        for (int i = 1; i < allTextLines.size(); i++) {
            TextLine currentItem = allTextLines.get(i);
            TextLine groupReferenceItem = currentLineGroup.get(0);

            if (Math.abs(groupReferenceItem.centerY() - currentItem.centerY()) < yAxisTolerance) {
                currentLineGroup.add(currentItem);
            } else {
                // 오차를 벗어나면 이전 그룹을 하나로 병합하여 결과에 넣고, 새로운 그룹 시작
                mergedResult.add(mergeTextLines(currentLineGroup));
                
                currentLineGroup = new ArrayList<>();
                currentLineGroup.add(currentItem);
            }
        }

        // 3. 마지막으로 남은 그룹 병합 처리
        if (!currentLineGroup.isEmpty()) {
            mergedResult.add(mergeTextLines(currentLineGroup));
        }

        return mergedResult;
    }

    // ========================================================================
    // 비공개 헬퍼 메서드 
    // ========================================================================

    /**
     * OpenCV Mat 객체(다각형 좌표)로부터 최소/최대 좌표를 추출하여 TextLine 객체를 생성합니다.
     */
    private static TextLine createTextLineFromMat(String text, Mat boxMat) {
        int totalElements = (int) (boxMat.total() * boxMat.channels());
        float[] coordinates = new float[totalElements];
        boxMat.get(0, 0, coordinates);

        int pointCount = coordinates.length / 2;

        IntSummaryStatistics xStats = IntStream.range(0, pointCount)
                .map(i -> (int) coordinates[i * 2 + AppConstants.Policy.COORD_DIM - 2])
                .summaryStatistics();

        IntSummaryStatistics yStats = IntStream.range(0, pointCount)
                .map(i -> (int) coordinates[i * 2 + AppConstants.Policy.COORD_DIM - 1])
                .summaryStatistics();

        double totalY = IntStream.range(0, pointCount)
                .mapToDouble(i -> coordinates[i * 2 + 1])
                .sum();
                
        double calculatedCenterY = totalY / pointCount;

        Rect boundingBox = new Rect(
                xStats.getMin(), 
                yStats.getMin(), 
                xStats.getMax() - xStats.getMin(), 
                yStats.getMax() - yStats.getMin()
        );

        return new TextLine(text, boundingBox, calculatedCenterY);
    }

    /**
     * 같은 줄로 판별된 여러 개의 TextLine 객체를 좌측에서 우측 순서로 하나의 라인으로 병합합니다.
     */
    private static TextLine mergeTextLines(List<TextLine> textLines) {
        if (textLines == null || textLines.isEmpty()) {
            return null;
        }

        // X 좌표를 기준으로 좌측에서 우측으로 정렬
        List<TextLine> sortedLines = textLines.stream()
                .sorted(Comparator.comparingInt(line -> line.rect().x))
                .toList();

        // 텍스트를 공백 기준으로 이어 붙임
        String mergedText = sortedLines.stream()
                .map(TextLine::text)
                .collect(Collectors.joining(" "));

        // 모든 작은 Rect들을 포함하는 하나의 큰 Rect로 병합
        Rect mergedRect = sortedLines.stream()
                .map(TextLine::rect)
                .reduce(BoxUtil::combineRectangles)
                .orElse(sortedLines.get(0).rect());

        // Y축 중심점의 평균값 계산
        double averageCenterY = sortedLines.stream()
                .mapToDouble(TextLine::centerY)
                .average()
                .orElse(0.0);

        return new TextLine(mergedText, mergedRect, averageCenterY);
    }

    /**
     * 두 사각형을 모두 포함하는 최소 크기의 사각형(Bounding Box) 영역을 계산합니다.
     */
    private static Rect combineRectangles(Rect rect1, Rect rect2) {
        int minX = Math.min(rect1.x, rect2.x);
        int minY = Math.min(rect1.y, rect2.y);
        
        int maxX = Math.max(rect1.x + rect1.width, rect2.x + rect2.width);
        int maxY = Math.max(rect1.y + rect1.height, rect2.y + rect2.height);
        
        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }
}
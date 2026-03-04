package com.example.ocr.domain;

import java.util.Arrays;

/**
 * AI 모델이 탐지한 문서 레이아웃(영역)의 종류를 정의하는 열거형입니다.
 * 각 레이아웃 타입별 고유 식별 코드와 시각화 색상(OpenCV BGR) 정보를 포함합니다.
 */
public enum LayoutType implements Visualizable {

    // ========================================================================
    // 열거형 상수 
    // ========================================================================
    TEXT           ("text",           Color.BLUE),
    TITLE          ("title",          Color.GREEN),
    FIGURE         ("figure",         Color.MAGENTA),
    FIGURE_CAPTION ("figure_caption", Color.MAGENTA),
    TABLE          ("table",          Color.RED),
    TABLE_CAPTION  ("table_caption",  Color.RED),
    HEADER         ("header",         Color.GRAY),
    FOOTER         ("footer",         Color.GRAY),
    REFERENCE      ("reference",      Color.ORANGE),
    EQUATION       ("equation",       Color.CYAN),
    UNKNOWN        ("unknown",        Color.UNKNOWN);

    // ========================================================================
    // 내부 데이터 구조 
    // ========================================================================

    /**
     * 시각화(VisualService) 시 OpenCV Bounding Box 테두리에 사용할 색상(BGR 포맷)
     * (OpenCV는 RGB가 아닌 BGR 순서를 사용함을 유의)
     */
    private static class Color {
        static final double[] BLUE    = {255.0, 0.0, 0.0};
        static final double[] GREEN   = {0.0, 255.0, 0.0};
        static final double[] RED     = {0.0, 0.0, 255.0};
        static final double[] GRAY    = {128.0, 128.0, 128.0};
        static final double[] MAGENTA = {255.0, 0.0, 255.0};
        static final double[] ORANGE  = {0.0, 165.0, 255.0};
        static final double[] CYAN    = {255.0, 255.0, 0.0};
        static final double[] UNKNOWN = {200.0, 200.0, 200.0};
    }

    // ========================================================================
    // 상태 변수
    // ========================================================================

    private final String typeCode;
    private final double[] visualizationColor;

    // ========================================================================
    // 생성자
    // ========================================================================

    LayoutType(String typeCode, double[] visualizationColor) {
        this.typeCode = typeCode;
        this.visualizationColor = visualizationColor;
    }

    // ========================================================================
    // 공개 메서드
    // ========================================================================

    public String getTypeCode() { 
        return this.typeCode; 
    }

    @Override 
    public String getKey() { 
        return this.typeCode; 
    }

    @Override 
    public double[] getColorComponents() { 
        return this.visualizationColor; 
    }

    /**
     * 모델이 반환한 문자열 코드로부터 해당하는 레이아웃 타입을 찾습니다.
     * 일치하는 타입이 없거나 입력값이 null인 경우 UNKNOWN을 반환합니다.
     *
     * @param code 검색할 레이아웃 코드 (예: "table", "text")
     * @return 매칭된 LayoutType (기본값: UNKNOWN)
     */
    public static LayoutType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }

        return Arrays.stream(values())
                .filter(type -> type.typeCode.equalsIgnoreCase(code))
                .findFirst()
                .orElse(UNKNOWN);
    }

    /**
     * 현재 레이아웃 타입이 표(Table) 영역인지 확인합니다.
     */
    public boolean isTable() { 
        return this == TABLE || this == TABLE_CAPTION; 
    }
}
package com.example.ocr.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.example.ocr.constant.AppConstants;

/**
 * 법원 결정문(Decision)에서 추출해야 할 핵심 데이터 항목들을 정의하는 열거형입니다.
 * 각 항목의 데이터 구조(단일/리스트 여부), 시각화 색상(OpenCV BGR), 파싱 키워드 정보를 포함합니다.
 */
public enum DecisionField implements Visualizable {

    // ========================================================================
    // 열거형 상수 
    // ========================================================================

    COURT       (FieldStructure.SINGLE, Color.COURT,        AppConstants.Field.COURT),
    INCIDENT    (FieldStructure.SINGLE, Color.INCIDENT,     AppConstants.Field.INCIDENT),
    CREDITOR    (FieldStructure.LIST,   Color.CREDITOR,     AppConstants.Field.CREDITOR),
    DEBTOR      (FieldStructure.LIST,   Color.DEBTOR,       AppConstants.Field.DEBTOR),
    THIRD_DEBTOR(FieldStructure.LIST,   Color.THIRD_DEBTOR, AppConstants.Field.THIRD_DEBTOR),
    ORDER       (FieldStructure.LIST,   Color.ORDER,        AppConstants.Field.ORDER),
    AMOUNT      (FieldStructure.SINGLE, Color.AMOUNT,       AppConstants.Field.AMOUNT),
    REASON      (FieldStructure.SINGLE, Color.REASON,       AppConstants.Field.REASON);

    // ========================================================================
    // 내부 데이터 구조
    // ========================================================================

    /**
     * 해당 필드가 가지는 데이터의 형태 (단일 문자열인지, 목록인지 구분)
     */
    public enum FieldStructure { 
        SINGLE, 
        LIST 
    }

    /**
     * 시각화(VisualService) 시 OpenCV Bounding Box 테두리에 사용할 색상(BGR 포맷) 정의
     * (RGB가 아닌 BGR 순서임을 유의)
     */
    private static class Color {
        private static final double[] COURT        = {60.0, 20.0, 220.0};
        private static final double[] INCIDENT     = {255.0, 144.0, 30.0};
        private static final double[] CREDITOR     = {211.0, 0.0, 148.0};
        private static final double[] DEBTOR       = {0.0, 69.0, 255.0};
        private static final double[] THIRD_DEBTOR = {34.0, 139.0, 34.0};
        private static final double[] ORDER        = {128.0, 128.0, 0.0};
        private static final double[] AMOUNT       = {133.0, 21.0, 199.0};
        private static final double[] REASON       = {19.0, 69.0, 139.0};
    }

    // ========================================================================
    // 상태 변수 
    // ========================================================================

    private final FieldStructure expectedStructure;
    private final double[] visualizationColor;
    private final List<String> parsingKeywords;

    /**
     * 키워드(공백 제거)를 기반으로 DecisionField를 빠르게 찾기 위한 캐시 맵
     */
    private static final Map<String, DecisionField> KEYWORD_LOOKUP_MAP = Arrays.stream(values())
            .flatMap(field -> field.parsingKeywords.stream()
                    .map(keyword -> Map.entry(removeSpaces(keyword), field))
            )
            .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, 
                    Map.Entry::getValue, 
                    (existing, replacement) -> existing // 중복 키 매핑 발생 시 기존 값 유지
            ));

    // ========================================================================
    // 생성자 
    // ========================================================================

    DecisionField(FieldStructure expectedStructure, double[] visualizationColor, String... parsingKeywords) {
        this.expectedStructure = expectedStructure;
        this.visualizationColor = visualizationColor;
        this.parsingKeywords = List.of(parsingKeywords);
    }

    // ========================================================================
    // 공개 메서드
    // ========================================================================

    @Override 
    public String getKey() { 
        return this.parsingKeywords.get(0); 
    }
    
    @Override 
    public double[] getColorComponents() { 
        return this.visualizationColor; 
    }
    
    public boolean isListType() { 
        return this.expectedStructure == FieldStructure.LIST; 
    }

    /**
     * 문서 상에서 항목들이 파싱되어야 할 일반적인 순서를 반환합니다.
     * (열거형 상수가 선언된 순서와 동일하게 유지됨)
     */
    public static List<DecisionField> parsingOrder() { 
        return List.of(values()); 
    }
    
    /**
     * 텍스트(키워드)로부터 매칭되는 DecisionField를 찾습니다.
     * 공백을 무시하고 검색합니다.
     *
     * @param targetKey 찾을 키워드
     */
    public static Optional<DecisionField> fromKey(String targetKey) { 
        return Optional.ofNullable(KEYWORD_LOOKUP_MAP.get(removeSpaces(targetKey))); 
    }

    // ========================================================================
    // 비공개 유틸리티 메서드
    // ========================================================================

    /**
     * 매칭 정확도를 높이기 위해 검색어의 모든 공백을 제거합니다.
     */
    private static String removeSpaces(String text) { 
        if (text == null) {
            return "";
        }
        return text.replace(" ", "");
    }
}
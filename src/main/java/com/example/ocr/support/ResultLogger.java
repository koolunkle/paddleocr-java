package com.example.ocr.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ocr.dto.AnalysisResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * OCR 분석 결과를 가독성 있게 포맷팅하여 로그로 출력하는 컴포넌트
 */
@Component
public class ResultLogger {

    // ========================================================================
    // 상수 
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(ResultLogger.class);

    /** 로그 출력 시 사용될 가로선의 총 길이 */
    private static final int MAX_LINE_WIDTH = 80;
    
    /** 텍스트 중앙 정렬 시 양옆 여백이나 테두리에 사용할 문자 */
    private static final String BORDER_CHAR = "=";
    
    /** 상/하단 구분을 위한 전체 길이의 가로선 */
    private static final String SEPARATOR_BAR = BORDER_CHAR.repeat(MAX_LINE_WIDTH);

    /** ASCII 문자 판별 임계값 (영어/숫자/기본 기호 등) */
    private static final int MAX_ASCII_CODE = 127;
    
    /** 문자별 출력 너비 (영문/숫자는 1칸, 한글 등 다국어는 2칸으로 계산) */
    private static final int HALF_WIDTH = 1;
    private static final int FULL_WIDTH = 2;

    // ========================================================================
    // 상태 변수 
    // ========================================================================

    private final ObjectWriter decisionWriter;
    private final ObjectWriter rawWriter;

    // ========================================================================
    // 생성자 
    // ========================================================================

    public ResultLogger(ObjectMapper objectMapper) {
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        
        // 현재는 동일한 포맷터를 사용하지만, 추후 타입별로 직렬화 옵션이
        // 달라질 수 있음을 고려하여 각각의 Writer로 분리 유지
        this.decisionWriter = objectMapper.writer(prettyPrinter);
        this.rawWriter = objectMapper.writer(prettyPrinter);
    }

    // ========================================================================
    // 공개 메서드
    // ========================================================================

    /**
     * 페이지 단위의 분석 결과를 콘솔(로그)에 보기 좋게 규격화하여 출력합니다.
     *
     * @param pageResult 출력할 페이지 분석 결과 객체
     */
    public void logPageResult(AnalysisResponse.PageResult pageResult) {
        if (pageResult == null || pageResult.data() == null) {
            return;
        }

        try {
            String formattedJson = serializeToJson(pageResult);
            String headerText = " [Result] Page: %d | Type: %s ".formatted(pageResult.pageNum(), pageResult.type());
            
            log.info("""

                    {}
                    {}
                    {}
                    {}
                    {}""", 
                    SEPARATOR_BAR, 
                    centerText(headerText), 
                    SEPARATOR_BAR, 
                    formattedJson, 
                    SEPARATOR_BAR
            );
        } catch (JsonProcessingException e) {
            log.error("[LogUtil] Failed to serialize page result (Page: {})", pageResult.pageNum(), e);
        }
    }

    // ========================================================================
    // 비공개 메서드
    // ========================================================================

    /**
     * 분석 타입(DECISION, RAW)에 맞게 DTO 객체를 JSON 문자열로 직렬화합니다.
     */
    private String serializeToJson(AnalysisResponse.PageResult pageResult) throws JsonProcessingException {
        return switch (pageResult.type()) {
            case DECISION -> this.decisionWriter.writeValueAsString(pageResult.data());
            case RAW -> this.rawWriter.writeValueAsString(pageResult.data());
        };
    }

    /**
     * 지정된 텍스트를 중앙에 배치하고, 남은 공간을 BORDER_CHAR 기호로 채웁니다.
     */
    private String centerText(String text) {
        int textDisplayLength = calculateDisplayLength(text);
        int totalPadding = Math.max(0, MAX_LINE_WIDTH - textDisplayLength);
        
        int leftPadding = totalPadding / 2;
        int rightPadding = totalPadding - leftPadding;
        
        return BORDER_CHAR.repeat(leftPadding) + text + BORDER_CHAR.repeat(rightPadding);
    }

    /**
     * 문자열이 콘솔에 출력될 때 차지하는 실제 너비를 계산합니다.
     * (전각 문자와 반각 문자의 너비 차이 보정)
     */
    private int calculateDisplayLength(String text) {
        return text.chars().map(character -> {
            if (character <= MAX_ASCII_CODE) {
                return HALF_WIDTH;
            }
            return FULL_WIDTH;
        }).sum();
    }
}
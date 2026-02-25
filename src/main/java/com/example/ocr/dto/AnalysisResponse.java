package com.example.ocr.dto;

import java.util.List;

import com.example.ocr.constant.AppConstants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OCR 통합 분석 결과를 클라이언트에게 반환하는 최상위 응답(Response) DTO입니다.
 *
 * @param fileName 분석이 완료된 원본 파일의 이름
 * @param results  페이지별 분석 결과 목록
 */
public record AnalysisResponse(
        String fileName, 
        List<PageResult> results
) {

    // ========================================================================
    // 내부 열거형
    // ========================================================================

    /**
     * 페이지 분석 결과의 데이터 유형을 정의합니다.
     */
    public enum Type {
        
        /** 정형화되지 않은 일반 OCR 추출 결과 (레이아웃, 단순 텍스트, 표 구조 등) */
        RAW(AppConstants.TYPE_RAW),
        
        /** 특정 도메인(예: 법원 결정문) 템플릿에 맞춰 파싱 및 구조화된 데이터 결과 */
        DECISION(AppConstants.TYPE_DECISION);

        private final String value;

        Type(String value) { 
            this.value = value; 
        }

        /**
         * JSON 직렬화 시 Enum의 이름(RAW, DECISION) 대신 
         * AppConstants에 정의된 실제 문자열 값을 출력하도록 지정합니다.
         */
        @JsonValue 
        public String getValue() { 
            return this.value; 
        }
    }

    // ========================================================================
    // 내부 레코드
    // ========================================================================

    /**
     * 단일 페이지에 대한 OCR 및 파싱 분석 결과를 담는 레코드입니다.
     *
     * @param pageNum 현재 페이지 번호 (JSON 속성명: "page_num")
     * @param type    해당 페이지의 데이터 유형 (RAW 또는 DECISION)
     * @param data    실제 추출된 세부 데이터 (null일 경우 JSON 응답에서 제외됨)
     */
    public record PageResult(
            @JsonProperty("page_num") 
            int pageNum,
            
            Type type,
            
            @JsonInclude(JsonInclude.Include.NON_NULL) 
            PageData data
    ) {}
}
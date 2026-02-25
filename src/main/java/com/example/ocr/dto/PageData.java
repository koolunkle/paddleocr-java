package com.example.ocr.dto;

import java.util.List;

import com.example.ocr.constant.AppConstants;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 페이지 단위의 OCR 분석 결과를 담는 다형성 DTO 인터페이스입니다.
 * Jackson의 @JsonTypeInfo를 활용하여, 메타 타입(RAW, DECISION) 값에 따라 
 * 알맞은 구현체(Record)로 자동 직렬화 및 역직렬화됩니다.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME, 
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = AppConstants.Field.META_TYPE, 
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = PageData.Raw.class, name = AppConstants.TYPE_RAW),
        @JsonSubTypes.Type(value = PageData.Decision.class, name = AppConstants.TYPE_DECISION)
})
public sealed interface PageData permits PageData.Raw, PageData.Decision {

    // ========================================================================
    // 다형성 구현체
    // ========================================================================

    /**
     * 1. 일반 분석 모드 (RAW)
     * 정형화되지 않은 일반 OCR 분석 결과 (레이아웃 영역, 텍스트 라인, 표 구조 등)
     *
     * @param regions 탐지된 레이아웃 영역 목록
     * @param error   분석 중 발생한 에러 메시지 (없을 경우 JSON 응답에서 제외됨)
     */
    record Raw(
            List<Region> regions, 
            
            @JsonInclude(JsonInclude.Include.NON_NULL) 
            String error
    ) implements PageData {}

    /**
     * 2. 템플릿 파싱 모드 (DECISION)
     * 법원 결정문 등의 특정 도메인 템플릿에 맞추어 구조화된 데이터 결과
     *
     * @param structuredData 파싱이 완료된 구조화 데이터 
     * (@JsonUnwrapped를 통해 JSON 응답 시 평탄화하여 출력)
     */
    record Decision(
            @JsonUnwrapped 
            Result structuredData
    ) implements PageData {}


    // ========================================================================
    // 하위 데이터 모델 - RAW 계층
    // ========================================================================

    /**
     * 탐지된 개별 레이아웃 영역(텍스트 블록, 표, 이미지 등)의 상세 정보
     */
    record Region(
            @JsonProperty("type") 
            String type,
            
            @JsonProperty("score") 
            float score,
            
            @JsonProperty("rect") 
            Rect rect,
            
            @JsonProperty("lines") 
            @JsonInclude(JsonInclude.Include.NON_EMPTY) 
            List<String> lines,
            
            @JsonProperty("structure") 
            @JsonInclude(JsonInclude.Include.NON_NULL) 
            TableStructure structure
    ) {}

    /**
     * 표(Table) 형태의 레이아웃 영역에 대한 행/열 구조 정보
     */
    record TableStructure(
            @JsonProperty("header") 
            List<Cell> header,
            
            @JsonProperty("rows") 
            List<Row> rows
    ) {}

    /**
     * 표 내부의 단일 행(Row) 정보
     */
    record Row(
            @JsonProperty("cells") 
            List<Cell> cells
    ) {}

    /**
     * 표 내부의 단일 셀(Cell) 정보
     */
    record Cell(
            @JsonProperty("text") 
            String text,
            
            @JsonProperty("row") 
            @JsonInclude(JsonInclude.Include.NON_NULL) 
            Integer row,
            
            @JsonProperty("col") 
            @JsonInclude(JsonInclude.Include.NON_NULL) 
            Integer col,
            
            @JsonProperty("colspan") 
            @JsonInclude(JsonInclude.Include.NON_NULL) 
            Integer colspan,
            
            @JsonProperty("rowspan") 
            @JsonInclude(JsonInclude.Include.NON_NULL) 
            Integer rowspan,
            
            @JsonProperty("rect") 
            Rect rect
    ) {}

    /**
     * 텍스트나 레이아웃의 공간 좌표 및 크기(Bounding Box) 정보
     */
    record Rect(
            @JsonProperty("x") int x, 
            @JsonProperty("y") int y, 
            @JsonProperty("w") int width, 
            @JsonProperty("h") int height
    ) {}


    // ========================================================================
    // 하위 데이터 모델 - DECISION 계층
    // ========================================================================

    /**
     * 법원 결정문 등의 템플릿 파싱을 통해 추출된 최종 도메인 데이터
     * (null인 필드는 JSON 응답에서 제외됩니다.)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Result(
            @JsonProperty(AppConstants.Field.COURT) 
            String court,
            
            @JsonProperty(AppConstants.Field.INCIDENT) 
            String incident,
            
            @JsonProperty(AppConstants.Field.CREDITOR) 
            List<String> creditor,
            
            @JsonProperty(AppConstants.Field.DEBTOR) 
            List<String> debtor,
            
            @JsonProperty(AppConstants.Field.THIRD_DEBTOR) 
            List<String> thirdDebtor,
            
            @JsonProperty(AppConstants.Field.ORDER) 
            List<String> order,
            
            @JsonProperty(AppConstants.Field.AMOUNT) 
            String amount,
            
            @JsonProperty(AppConstants.Field.REASON) 
            String reason
    ) {}
}
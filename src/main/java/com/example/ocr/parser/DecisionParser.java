package com.example.ocr.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.springframework.stereotype.Component;

import com.example.ocr.config.AppProperties;
import com.example.ocr.constant.AppConstants;
import com.example.ocr.domain.DecisionField;
import com.example.ocr.dto.PageData.Result;
import com.example.ocr.util.BoxUtil;
import com.example.ocr.util.BoxUtil.TextLine;
import com.example.ocr.util.TextUtil;

/**
 * OCR로 인식된 텍스트와 좌표 데이터를 기반으로,
 * 법원 결정문(Decision)의 주요 항목들을 추출하고 파싱하는 컴포넌트
 */
@Component
public class DecisionParser {

    // ========================================================================
    // 상수 및 정규식 패턴
    // ========================================================================

    private static final String NOISE_PATTERN = "[^가-힣a-zA-Z]*";
    
    private static final Pattern COURT_HEADER_PATTERN = Pattern.compile("법" + NOISE_PATTERN + "원");
    private static final Pattern DECISION_HEADER_PATTERN = Pattern.compile("결" + NOISE_PATTERN + "정");
    
    private static final Pattern DATE_REGEX = Pattern.compile("(19|20)\\d{2}\\.\\s*\\d{1,2}\\.\\s*\\d{1,2}");
    private static final Pattern CASE_NUMBER_FALLBACK = Pattern.compile(".*(19|20)\\d{2}[가-힣]{1,3}\\d+.*");
    private static final Pattern ITEM_SPLIT_PATTERN = Pattern.compile("(?<!\\d)" + Pattern.quote(AppConstants.Policy.SP) + "(?=\\d{1,2}([\\.\\)]|[가-힣]))");

    // ========================================================================
    // 상태 변수 
    // ========================================================================

    private final AppProperties appProperties;
    private final Map<String, Pattern> dynamicPatternCache = new ConcurrentHashMap<>();

    // ========================================================================
    // 내부 데이터 구조
    // ========================================================================

    public record ParseResult(Result data, Map<String, List<Rect>> boundingBoxes) {}
    
    private record DocumentAnchor(int courtLineIndex, int decisionLineIndex) {}
    
    private record RawExtractedData(
            Map<DecisionField, List<String>> extractedTexts, 
            Map<DecisionField, List<Rect>> extractedBoxes, 
            String courtName, 
            Rect courtBox
    ) {}

    // ========================================================================
    // 생성자
    // ========================================================================

    public DecisionParser(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // ========================================================================
    // 공개 메서드 
    // ========================================================================

    public ParseResult parse(List<String> rawTexts, List<Mat> boxMats) {
        int lineMergingGap = this.appProperties.algorithm().lineMergingVerticalGap();
        List<TextLine> textLines = BoxUtil.groupByLines(rawTexts, boxMats, lineMergingGap);

        return findDocumentAnchor(textLines)
                .map(anchor -> extractFieldsFromLines(textLines, anchor))
                .map(this::convertToParseResult)
                .orElse(null);
    }

    // ========================================================================
    // 비공개 메서드 - 앵커 탐색
    // ========================================================================

    private Optional<DocumentAnchor> findDocumentAnchor(List<TextLine> textLines) {
        int maxSearchLines = Math.min(this.appProperties.algorithm().headerSearchLineLimit(), textLines.size());
        
        return findCourtLineIndex(textLines, maxSearchLines)
                .flatMap(courtIdx -> findDecisionLineIndex(textLines, courtIdx, maxSearchLines)
                        .map(decisionIdx -> new DocumentAnchor(courtIdx, decisionIdx)));
    }

    private Optional<Integer> findCourtLineIndex(List<TextLine> textLines, int maxSearchLines) {
        int searchLimit = Math.min(this.appProperties.algorithm().courtHeaderSearchLimit(), maxSearchLines);
        
        return IntStream.range(0, searchLimit)
                .filter(index -> isKeywordMatch(textLines.get(index).text(), COURT_HEADER_PATTERN, DecisionField.COURT))
                .boxed()
                .findFirst();
    }

    private Optional<Integer> findDecisionLineIndex(List<TextLine> textLines, int courtLineIndex, int maxSearchLines) {
        int searchEndIndex = Math.min(courtLineIndex + this.appProperties.algorithm().decisionHeaderSearchOffset(), maxSearchLines);
        
        return IntStream.range(courtLineIndex + 1, searchEndIndex)
                .filter(index -> DECISION_HEADER_PATTERN.matcher(textLines.get(index).text()).find())
                .boxed()
                .findFirst();
    }

    private boolean isKeywordMatch(String text, Pattern fallbackPattern, DecisionField targetField) {
        double requiredSimilarity = this.appProperties.algorithm().fuzzyMatchSimilarity();
        return TextUtil.isFuzzyMatch(text, targetField.getKey(), requiredSimilarity) || fallbackPattern.matcher(text).find();
    }

    // ========================================================================
    // 비공개 메서드 - 본문 추출 로직
    // ========================================================================

    private RawExtractedData extractFieldsFromLines(List<TextLine> textLines, DocumentAnchor anchor) {
        ParserContext context = new ParserContext();
        List<DecisionField> parsingOrderFields = DecisionField.parsingOrder();
        int currentFieldCursor = 0;

        for (int i = anchor.decisionLineIndex() + 1; i < textLines.size(); i++) {
            TextLine currentLine = textLines.get(i);
            
            if (shouldStopParsing(textLines, i)) {
                break;
            }

            Optional<DecisionField> nextMatchedField = findNextField(currentLine.text(), parsingOrderFields, currentFieldCursor);
            
            if (nextMatchedField.isPresent()) {
                DecisionField foundField = nextMatchedField.get();
                context.switchToNewField(foundField);
                currentFieldCursor = parsingOrderFields.indexOf(foundField) + 1;
                
                String textWithoutKeyword = stripFieldKeyword(currentLine.text(), foundField.getKey());
                context.addTextAndBox(textWithoutKeyword, currentLine);
                
            } else if (context.hasActiveField()) {
                context.addTextAndBox(currentLine.text(), currentLine);
                
            } else if (CASE_NUMBER_FALLBACK.matcher(currentLine.text()).matches()) {
                context.switchToNewField(DecisionField.INCIDENT);
                context.addTextAndBox(currentLine.text(), currentLine);
            }
        }
        
        context.flushBuffer();
        
        TextLine courtLine = textLines.get(anchor.courtLineIndex());
        String sanitizedCourtName = TextUtil.sanitize(courtLine.text());
        
        return new RawExtractedData(
                context.extractedTexts, 
                context.extractedBoxes, 
                sanitizedCourtName, 
                courtLine.rect()
        );
    }

    private Optional<DecisionField> findNextField(String text, List<DecisionField> fields, int searchStartIndex) {
        double requiredSimilarity = this.appProperties.algorithm().fuzzyMatchSimilarity();
        
        return fields.stream()
                .skip(searchStartIndex)
                .filter(field -> TextUtil.isFuzzyMatch(text, field.getKey(), requiredSimilarity))
                .findFirst();
    }

    private String stripFieldKeyword(String originalText, String keywordToStrip) {
        Pattern dynamicPattern = this.dynamicPatternCache.computeIfAbsent(keywordToStrip, key -> {
            String regexWithNoise = key.chars()
                    .mapToObj(character -> Pattern.quote(String.valueOf((char) character)))
                    .collect(Collectors.joining(NOISE_PATTERN));
            return Pattern.compile(regexWithNoise);
        });
        
        Matcher matcher = dynamicPattern.matcher(originalText);
        
        if (matcher.find()) {
            return originalText.replaceFirst(Pattern.quote(matcher.group()), " ").trim();
        }
        return originalText.replaceFirst(Pattern.quote(keywordToStrip), " ").trim();
    }

    private boolean shouldStopParsing(List<TextLine> textLines, int currentIndex) {
        TextLine currentLine = textLines.get(currentIndex);
        
        if (DATE_REGEX.matcher(currentLine.text()).find()) {
            return true;
        }
        if (currentIndex <= 0) {
            return false;
        }
        
        TextLine previousLine = textLines.get(currentIndex - 1);
        double verticalGap = currentLine.rect().y - (previousLine.rect().y + previousLine.rect().height);
        
        return verticalGap > this.appProperties.algorithm().sectionBreakThreshold();
    }

    // ========================================================================
    // 비공개 메서드 - 결과 변환 및 가공
    // ========================================================================

    private ParseResult convertToParseResult(RawExtractedData rawData) {
        Map<String, List<Rect>> groupedBoundingBoxes = new LinkedHashMap<>();
        
        Optional.ofNullable(rawData.courtName())
                .ifPresent(court -> groupedBoundingBoxes.put(DecisionField.COURT.getKey(), List.of(rawData.courtBox())));
                
        rawData.extractedTexts().forEach((field, textList) -> 
                groupedBoundingBoxes.put(field.getKey(), rawData.extractedBoxes().get(field))
        );

        Result finalResultData = new Result(
                rawData.courtName(), 
                joinTexts(rawData.extractedTexts(), DecisionField.INCIDENT),
                splitTexts(rawData.extractedTexts(), DecisionField.CREDITOR), 
                splitTexts(rawData.extractedTexts(), DecisionField.DEBTOR),
                splitTexts(rawData.extractedTexts(), DecisionField.THIRD_DEBTOR), 
                splitTexts(rawData.extractedTexts(), DecisionField.ORDER),
                joinTexts(rawData.extractedTexts(), DecisionField.AMOUNT), 
                joinTexts(rawData.extractedTexts(), DecisionField.REASON)
        );

        return new ParseResult(finalResultData, groupedBoundingBoxes);
    }

    private String joinTexts(Map<DecisionField, List<String>> extractedTexts, DecisionField field) {
        List<String> values = extractedTexts.get(field);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(AppConstants.Policy.SP, values);
    }

    private List<String> splitTexts(Map<DecisionField, List<String>> extractedTexts, DecisionField field) {
        return Optional.ofNullable(joinTexts(extractedTexts, field))
                .map(fullText -> Arrays.stream(ITEM_SPLIT_PATTERN.split(fullText))
                        .map(String::trim)
                        .filter(text -> !text.isEmpty())
                        .toList())
                .orElse(null);
    }

    // ========================================================================
    // 내부 클래스 
    // ========================================================================

    /**
     * 문서를 순차적으로 읽어나갈 때, 현재 파싱 중인 항목의 상태와 
     * 누적된 텍스트/좌표를 관리하는 컨텍스트 헬퍼 클래스
     */
    private static class ParserContext {
        final Map<DecisionField, List<String>> extractedTexts = new LinkedHashMap<>();
        final Map<DecisionField, List<Rect>> extractedBoxes = new LinkedHashMap<>();
        
        private final List<String> textBuffer = new ArrayList<>();
        private final List<Rect> boxBuffer = new ArrayList<>();
        private DecisionField activeField;

        void switchToNewField(DecisionField targetField) {
            flushBuffer();
            this.activeField = targetField;
        }

        boolean hasActiveField() {
            return this.activeField != null;
        }

        void addTextAndBox(String text, TextLine textLine) {
            String sanitizedText = TextUtil.sanitize(text);
            if (!sanitizedText.isBlank()) {
                this.textBuffer.add(sanitizedText);
                this.boxBuffer.add(textLine.rect());
            }
        }

        /**
         * 버퍼에 누적된 데이터를 최종 맵에 저장하고 버퍼를 비웁니다.
         */
        void flushBuffer() {
            if (this.activeField != null && !this.textBuffer.isEmpty()) {
                this.extractedTexts.put(this.activeField, List.copyOf(this.textBuffer));
                this.extractedBoxes.put(this.activeField, List.copyOf(this.boxBuffer));
                
                this.textBuffer.clear();
                this.boxBuffer.clear();
            }
        }
    }
}
package com.example.ocr.util;

import java.util.regex.Pattern;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import com.example.ocr.constant.AppConstants;

/**
 * OCR 추출 텍스트의 정제(Sanitization) 및 유사도(Fuzzy Match) 비교를 담당하는
 * 상태를 가지지 않는 순수 유틸리티 클래스입니다.
 */
public final class TextUtil {

    // ========================================================================
    // 상수 및 정규식 패턴
    // ========================================================================

    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    /** 연속된 하나 이상의 공백문자 매칭 패턴 */
    private static final Pattern MULTI_WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /** 한글 및 영문자를 제외한 모든 문자 매칭 (숫자 포함 안 됨) */
    private static final Pattern NON_LETTER_PATTERN = Pattern.compile("[^가-힣a-zA-Z]");

    /** 시스템에서 허용하지 않는 불순물 특수문자 매칭 패턴 (한/영/숫자 및 일부 특수문자 외) */
    private static final Pattern DISALLOWED_CHAR_PATTERN = Pattern.compile("[^가-힣a-zA-Z0-9\\(\\)\\-\\[\\]\\<\\>\\! @#\\$%\\^&\\*\\s,\\.]");

    // ========================================================================
    // 생성자 차단
    // ========================================================================

    private TextUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ========================================================================
    // 공개 메서드
    // ========================================================================

    /**
     * 두 문자열의 Jaro-Winkler 유사도를 비교하여 임계치(threshold) 이상인지 확인합니다.
     *
     * @param source    원본 문자열
     * @param target    비교 대상 문자열
     * @param threshold 유사도 통과 임계치 (0.0 ~ 1.0)
     * @return 유사도 충족 여부
     */
    public static boolean isFuzzyMatch(String source, String target, double threshold) {
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            return false;
        }

        String cleanedSource = extractLetters(source);
        String cleanedTarget = extractLetters(target);

        if (cleanedSource.isEmpty() || cleanedTarget.isEmpty()) {
            return false;
        }

        // 한쪽이 다른 쪽을 완전히 포함하는 경우 비싼 연산 없이 즉시 통과 (Fast-path)
        if (cleanedSource.contains(cleanedTarget) || cleanedTarget.contains(cleanedSource)) {
            return true;
        }

        // Jaro-Winkler 연산 비용 최적화를 위해 타겟 길이 + 3 까지만 source를 잘라서 비교
        int compareLength = Math.min(cleanedSource.length(), cleanedTarget.length() + 3);
        String sourcePrefix = cleanedSource.substring(0, compareLength);

        return JARO_WINKLER.apply(sourcePrefix, cleanedTarget) >= threshold;
    }

    /**
     * 불필요한 특수문자 및 연속된 공백을 제거하여 텍스트를 정제합니다.
     *
     * @param text 원본 텍스트
     * @return 정제된 텍스트 (입력값이 없거나 정제 후 남는 게 없으면 빈 문자열 반환)
     */
    public static String sanitize(String text) {
        // 1. Null 또는 빈 문자열 검사 (Early Return)
        if (text == null || text.isBlank()) {
            return "";
        }

        // 2. 불순물 제거
        String sanitized = DISALLOWED_CHAR_PATTERN.matcher(text).replaceAll("");

        // 3. 앞뒤 공백 제거
        sanitized = sanitized.trim();
        if (sanitized.isEmpty()) {
            return "";
        }

        // 4. 내부의 연속된 공백을 단일 규격 공백으로 치환
        return MULTI_WHITESPACE_PATTERN.matcher(sanitized).replaceAll(AppConstants.Policy.SP);
    }

    // ========================================================================
    // 비공개 헬퍼 메서드
    // ========================================================================

    /**
     * 문자열에서 한글과 영문자만 남기고 모두 제거합니다.
     */
    private static String extractLetters(String text) {
        return NON_LETTER_PATTERN.matcher(text).replaceAll("");
    }
}

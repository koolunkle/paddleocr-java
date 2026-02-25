package com.example.ocr.util;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("TextUtil 단위 테스트")
class TextUtilTest {

    @Nested
    @DisplayName("sanitize() 메서드는")
    class Describe_sanitize {

        @Test
        @DisplayName("null 또는 빈 문자열이 주어지면 빈 문자열을 반환한다")
        void it_returns_empty_string_when_input_is_null_or_blank() {
            assertThat(TextUtil.sanitize(null)).isEmpty();
            assertThat(TextUtil.sanitize("   ")).isEmpty();
        }

        @Test
        @DisplayName("허용되지 않은 특수문자를 모두 제거한다")
        void it_removes_disallowed_characters() {
            // given (허용되지 않은 문자 '~', '`', '=', '+' 포함)
            String input = "법원~결정문` =테스트+";

            // when
            String result = TextUtil.sanitize(input);

            // then (내부의 연속된 공백을 단일 규격 공백으로 치환하는 과정에서 [SP]로 변환된다고 가정)
            assertThat(result).isEqualTo("법원결정문[SP]테스트");
        }

        @Test
        @DisplayName("연속된 공백문자(엔터, 탭 포함)를 단일 치환자([SP])로 변경한다")
        void it_replaces_multiple_whitespaces_with_placeholder() {
            // given
            String input = "서울중앙지방법원 \n\n 테 스트 \t 결정";

            // when
            String result = TextUtil.sanitize(input);

            // then (단일 치환자 값이 "[SP]" 라고 가정)
            assertThat(result).isEqualTo("서울중앙지방법원[SP]테[SP]스트[SP]결정");
        }
    }

    @Nested
    @DisplayName("isFuzzyMatch() 메서드는")
    class Describe_isFuzzyMatch {

        @ParameterizedTest(name = "\"{0}\" 와 \"{1}\" 비교 (임계치 0.7) -> {2}")
        @CsvSource({"서울중앙지방법원, 중앙지방법원, true", // 부분 포함 (Fast-path 통과)
                "채무자, 채무자, true", // 완전 일치
                "채무자, 체무자, true", // 오타 (유사도 통과)
                "채권자, 테스트, false" // 전혀 다른 단어
        })
        @DisplayName("Jaro-Winkler 알고리즘을 사용하여 두 문자열의 유사도를 판별한다")
        void it_calculates_fuzzy_match_correctly(String source, String target, boolean expected) {
            // when
            boolean isMatch = TextUtil.isFuzzyMatch(source, target, 0.7);

            // then
            assertThat(isMatch).isEqualTo(expected);
        }
    }
}

package com.example.ocr.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opencv.core.Rect;
import com.example.ocr.config.AppProperties;
import com.example.ocr.constant.AppConstants;
import com.example.ocr.dto.PageData;
import com.example.ocr.util.BoxUtil;
import com.example.ocr.util.BoxUtil.TextLine;

@ExtendWith(MockitoExtension.class)
@DisplayName("DecisionParser 단위 테스트")
class DecisionParserTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private AppProperties appProperties;

    @InjectMocks
    private DecisionParser decisionParser;

    @BeforeEach
    void setUp() {
        // 파서가 문서를 탐색할 수 있도록 알고리즘 설정값 주입
        given(appProperties.algorithm().lineMergingVerticalGap()).willReturn(15);
        given(appProperties.algorithm().headerSearchLineLimit()).willReturn(10);
        given(appProperties.algorithm().courtHeaderSearchLimit()).willReturn(5);
        given(appProperties.algorithm().decisionHeaderSearchOffset()).willReturn(4);
        given(appProperties.algorithm().fuzzyMatchSimilarity()).willReturn(0.7);
        given(appProperties.algorithm().sectionBreakThreshold()).willReturn(100);
    }

    @Test
    @DisplayName("정상적인 법원 결정문 텍스트 리스트가 주어지면 주요 필드를 추출하여 Result 객체로 반환해야 한다")
    void parse_decision_fields_successfully() {
        List<String> ocrTextLines = List.of(
                "서 울 중 앙 지 방 법 원",
                "결 정",
                "사 건 2023카단12345",
                "채 권 자 홍 길 동",
                "채 무 자 주식회사 테스트",
                "주 문",
                "1. 채무자의 제3채무자에 대한 별지 기재 채권을 가압류한다.",
                "2023. 10. 25."
        );

        // BoxUtil의 정적 메서드를 가로채서 가짜 TextLine 리스트 반환
        try (MockedStatic<BoxUtil> mockedBoxUtil = mockStatic(BoxUtil.class)) {

            // Y좌표를 30씩 증가시키며 가짜 TextLine들을 생성 (줄바꿈 오차 방지)
            List<TextLine> dummyTextLines = new ArrayList<>();
            for (int i = 0; i < ocrTextLines.size(); i++) {
                 // 1) TextLine 가짜 객체 생성
                TextLine mockLine = mock(TextLine.class);

                // 2) text() 와 rect() 호출 시 원하는 값을 반환하도록 조작
                lenient().when(mockLine.text()).thenReturn(ocrTextLines.get(i));
                lenient().when(mockLine.rect()).thenReturn(new Rect(0, i * 30, 100, 20));

                dummyTextLines.add(mockLine);
            }

            mockedBoxUtil.when(() -> BoxUtil.groupByLines(any(), any(), anyInt()))
                         .thenReturn(dummyTextLines);

            // When
            var decisionData = decisionParser.parse(ocrTextLines, List.of());

            // Then
            assertThat(decisionData).isNotNull();

            PageData.Result parsedResult = decisionData.data();

            String cleanCourt = parsedResult.court().replace(AppConstants.Policy.SP, "");
            assertThat(cleanCourt).isEqualTo("서울중앙지방법원");

            String cleanIncident = parsedResult.incident().replace(AppConstants.Policy.SP, "");
            assertThat(cleanIncident).contains("2023카단12345");

            String cleanCreditor = parsedResult.creditor().get(0).replace(AppConstants.Policy.SP, "");
            assertThat(cleanCreditor).contains("홍길동");

            String cleanDebtor = parsedResult.debtor().get(0).replace(AppConstants.Policy.SP, "");
            assertThat(cleanDebtor).contains("주식회사테스트");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "사 건 2023 카단 12345",
        "사건번호: 2023카단12345",
        "[사건] 2023 카단 12345"
    })
    @DisplayName("다양한 형태의 '사건' 노이즈 속에서도 정확히 포맷을 추출해야 한다")
    void extract_case_number_robustly_with_regex(String noisyText) {
        List<String> inputLines = List.of(
                "서울중앙지방법원",
                "결 정",
                noisyText,
                "채무자 김아무개",
                "2023. 10. 25."
        );

        // BoxUtil의 정적 메서드를 가로채서 가짜 TextLine 리스트 반환
        try (MockedStatic<BoxUtil> mockedBoxUtil = mockStatic(BoxUtil.class)) {

            // Y좌표를 30씩 증가시키며 가짜 TextLine들을 생성 (줄바꿈 오차 방지)
            List<TextLine> dummyTextLines = new ArrayList<>();
            for (int i = 0; i < inputLines.size(); i++) {
                // 1) TextLine 가짜 객체 생성
                TextLine mockLine = mock(TextLine.class);

                // 2) text() 와 rect() 호출 시 원하는 값을 반환하도록 조작
                lenient().when(mockLine.text()).thenReturn(inputLines.get(i));
                lenient().when(mockLine.rect()).thenReturn(new Rect(0, i * 30, 100, 20));

                dummyTextLines.add(mockLine);
            }

            mockedBoxUtil.when(() -> BoxUtil.groupByLines(any(), any(), anyInt()))
                         .thenReturn(dummyTextLines);

            // When
            var decisionData = decisionParser.parse(inputLines, List.of());

            // Then
            assertThat(decisionData).isNotNull();

            PageData.Result parsedResult = decisionData.data();
            assertThat(parsedResult.incident()).contains("2023");
        }
    }
}

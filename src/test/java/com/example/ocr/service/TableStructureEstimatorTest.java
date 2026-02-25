package com.example.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.benjaminwan.ocrlibrary.Point;
import com.benjaminwan.ocrlibrary.TextBlock;
import com.example.ocr.dto.PageData;
import com.example.ocr.parser.TableStructureEstimator;

@DisplayName("TableStructureEstimator 단위 테스트")
class TableStructureEstimatorTest {

    private TableStructureEstimator tableStructureEstimator;

    @BeforeEach
    void setUp() {
        this.tableStructureEstimator = new TableStructureEstimator();
    }

    @Test
    @DisplayName("빈 리스트가 주어지면 null(또는 빈 구조)을 반환해야 한다")
    void return_null_when_empty_blocks_given() {
        PageData.TableStructure result = tableStructureEstimator.estimate(List.of());
        assertThat(result).isNull(); // 구현에 따라 isEmpty() 등을 검증
    }

    @Test
    @DisplayName("2x2 배열 형태의 텍스트 블록이 주어지면 정확한 행렬 구조로 파싱해야 한다")
    void parse_2x2_table_structure_correctly() {
        // 1. Given (가상의 2x2 표 좌표 생성)
        List<TextBlock> mockBlocks = new ArrayList<>();

        // -- 첫 번째 행 (Y좌표가 10~30으로 비슷함)
        mockBlocks.add(createMockBlock("이름", 10, 10, 50, 30));    // (1행 1열)
        mockBlocks.add(createMockBlock("금액", 100, 10, 150, 30));  // (1행 2열)

        // -- 두 번째 행 (Y좌표가 50~70으로 비슷함, 순서를 섞어서 삽입해도 X좌표로 정렬되어야 함)
        mockBlocks.add(createMockBlock("50,000", 100, 50, 150, 70)); // (2행 2열)
        mockBlocks.add(createMockBlock("홍길동", 10, 50, 50, 70));     // (2행 1열)

        // 2. When (알고리즘 실행)
        PageData.TableStructure result = tableStructureEstimator.estimate(mockBlocks);

        // 3. Then (결과 검증)
        assertThat(result).isNotNull();
        assertThat(result.rows()).hasSize(2); // 2개의 행이 나와야 함

        // 첫 번째 행 검증
        List<String> firstRowTexts = result.rows().get(0).cells().stream()
                .map(PageData.Cell::text)
                .toList();
        assertThat(firstRowTexts).containsExactly("이름", "금액");

        // 두 번째 행 검증 (입력 순서가 섞였어도 X좌표 기준으로 잘 정렬되었는지 확인)
        List<String> secondRowTexts = result.rows().get(1).cells().stream()
                .map(PageData.Cell::text)
                .toList();
        assertThat(secondRowTexts).containsExactly("홍길동", "50,000");
    }

    // ========================================================================
    // 테스트 데이터 생성을 위한 Mock 헬퍼 메서드
    // ========================================================================

    /**
     * TextBlock Mock 객체 생성
     */
    private TextBlock createMockBlock(String text, int left, int top, int right, int bottom) {
        // 1) TextBlock 가짜 객체(Mock) 생성
        TextBlock block = mock(TextBlock.class);

        // 2) block.getText()가 호출되면 지정한 text를 반환하도록 조작
        when(block.getText()).thenReturn(text);

        // 3) 폴리곤 좌표 생성
        ArrayList<Point> boxPoints = new ArrayList<>(List.of(
                createMockPoint(left, top),     // 좌상
                createMockPoint(right, top),    // 우상
                createMockPoint(right, bottom), // 우하
                createMockPoint(left, bottom)   // 좌하
            )
        );

        // 4) block.getBoxPoint()가 호출되면 가짜 좌표 리스트를 반환하도록 조작
        when(block.getBoxPoint()).thenReturn(boxPoints);

        return block;
    }

    /**
     * Point Mock 객체 생성
     */
    private Point createMockPoint(int x, int y) {
        Point p = mock(Point.class);
        when(p.getX()).thenReturn(x);
        when(p.getY()).thenReturn(y);
        return p;
    }
}

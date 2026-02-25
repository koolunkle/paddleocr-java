package com.example.ocr.parser;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

import com.benjaminwan.ocrlibrary.Point;
import com.benjaminwan.ocrlibrary.TextBlock;
import com.example.ocr.dto.PageData;
import com.example.ocr.util.TextUtil;

/**
 * OCR 분석 결과 중 표(Table) 레이아웃으로 식별된 영역에 대해,
 * 내부 텍스트 블록들의 좌표를 분석하여 행(Row), 열(Col), 병합(Span) 구조를 추정하는 전용 컴포넌트입니다.
 */
@Component
public class TableStructureEstimator {

    // ========================================================================
    // 공개 메서드 
    // ========================================================================

    /**
     * 텍스트 블록들의 공간적 위치(Bounding Box)를 분석하여 테이블 구조를 역산(Estimate)합니다.
     *
     * @param textBlocks 표 영역 내부에 존재하는 텍스트 블록 목록
     * @return 추정된 테이블 구조 (헤더 및 행/열 정보), 블록이 없으면 null 반환
     */
    public PageData.TableStructure estimate(List<TextBlock> textBlocks) {
        if (textBlocks == null || textBlocks.isEmpty()) {
            return null;
        }

        // 1. Y 좌표를 기준으로 같은 줄(Row)에 있는 텍스트 블록들을 그룹화
        List<List<TextBlock>> groupedRows = groupTextBlocksIntoRows(textBlocks);
        if (groupedRows.isEmpty()) {
            return null;
        }

        // 2. 가장 많은 블록(셀)을 가진 행을 '기준 행(Reference Row)'으로 선정
        List<TextBlock> referenceRow = groupedRows.stream()
                .max(Comparator.comparingInt(List::size))
                .orElse(groupedRows.get(0));

        // 3. 기준 행의 X 좌표를 기반으로 열의 경계선(Column Divider)들을 계산
        List<Rectangle> referenceCellRectangles = referenceRow.stream()
                .map(this::convertToAwtRectangle)
                .sorted(Comparator.comparingInt(rect -> rect.x))
                .toList();

        List<Integer> columnDividers = IntStream.range(0, referenceCellRectangles.size() - 1)
                .mapToObj(index -> {
                    Rectangle current = referenceCellRectangles.get(index);
                    Rectangle next = referenceCellRectangles.get(index + 1);
                    return (current.x + current.width + next.x) / 2; // 두 셀 사이의 중간 지점
                })
                .toList();

        // 4. 각 행을 순회하며 열(Column) 인덱스와 병합(Colspan) 크기를 추정
        List<List<PageData.Cell>> mappedCellsPerRow = new ArrayList<>();
        
        for (int rowIndex = 0; rowIndex < groupedRows.size(); rowIndex++) {
            List<PageData.Cell> rowCells = new ArrayList<>();
            
            for (TextBlock block : groupedRows.get(rowIndex)) {
                Rectangle blockRect = convertToAwtRectangle(block);
                int startX = blockRect.x;
                int endX = blockRect.x + blockRect.width;
                
                int columnIndex = (int) columnDividers.stream().filter(dividerX -> startX > dividerX).count();
                int colSpanCount = (int) columnDividers.stream().filter(dividerX -> dividerX > startX && dividerX < endX).count();
                
                rowCells.add(new PageData.Cell(
                        TextUtil.sanitize(block.getText()), 
                        rowIndex + 1, 
                        columnIndex + 1, 
                        colSpanCount + 1, 
                        1, 
                        convertToDomainRect(blockRect)
                ));
            }
            
            rowCells.sort(Comparator.comparing(cell -> Objects.requireNonNullElse(cell.col(), 0)));
            mappedCellsPerRow.add(rowCells);
        }

        // 5. 첫 행이 기준 행보다 열이 적으면 Header로 간주하고 분리
        List<PageData.Cell> tableHeader = null;
        List<PageData.Row> tableRows = new ArrayList<>();
        
        if (!mappedCellsPerRow.isEmpty()) {
            int dataRowStartIndex = 0;
            
            if (mappedCellsPerRow.get(0).size() < referenceRow.size()) {
                tableHeader = mappedCellsPerRow.get(0).stream()
                        .map(cell -> new PageData.Cell(cell.text(), null, null, cell.colspan(), cell.rowspan(), cell.rect()))
                        .toList();
                dataRowStartIndex = 1;
            }
            
            for (int i = dataRowStartIndex; i < mappedCellsPerRow.size(); i++) {
                int adjustedRowIndex = i - dataRowStartIndex + 1;
                List<PageData.Cell> rowCells = mappedCellsPerRow.get(i).stream()
                        .map(cell -> new PageData.Cell(cell.text(), adjustedRowIndex, cell.col(), null, null, cell.rect()))
                        .toList();
                
                tableRows.add(new PageData.Row(rowCells));
            }
        }
        
        return new PageData.TableStructure(tableHeader, tableRows);
    }

    // ========================================================================
    // 비공개 헬퍼 메서드
    // ========================================================================

    private List<List<TextBlock>> groupTextBlocksIntoRows(List<TextBlock> textBlocks) {
        List<TextBlock> sortedByY = textBlocks.stream()
                .sorted(Comparator.comparingInt(block -> convertToAwtRectangle(block).y))
                .toList();

        List<Integer> heightList = textBlocks.stream()
                .map(block -> convertToAwtRectangle(block).height)
                .sorted()
                .toList();
        
        int medianHeight = heightList.isEmpty() ? 10 : heightList.get(heightList.size() / 2);
        int yAxisTolerance = medianHeight / 2;

        List<List<TextBlock>> rowGroups = new ArrayList<>();
        List<TextBlock> currentRowGroup = new ArrayList<>();

        for (TextBlock block : sortedByY) {
            if (!currentRowGroup.isEmpty()) {
                int referenceY = convertToAwtRectangle(currentRowGroup.get(0)).y;
                int currentY = convertToAwtRectangle(block).y;
                
                if (Math.abs(referenceY - currentY) > yAxisTolerance) {
                    rowGroups.add(new ArrayList<>(currentRowGroup));
                    currentRowGroup.clear();
                }
            }
            currentRowGroup.add(block);
        }

        if (!currentRowGroup.isEmpty()) {
            rowGroups.add(currentRowGroup);
        }

        return rowGroups;
    }

    private Rectangle convertToAwtRectangle(TextBlock textBlock) {
        IntSummaryStatistics xStats = textBlock.getBoxPoint().stream().mapToInt(Point::getX).summaryStatistics();
        IntSummaryStatistics yStats = textBlock.getBoxPoint().stream().mapToInt(Point::getY).summaryStatistics();
        
        return new Rectangle(
                xStats.getMin(), 
                yStats.getMin(), 
                xStats.getMax() - xStats.getMin(), 
                yStats.getMax() - yStats.getMin()
        );
    }

    private PageData.Rect convertToDomainRect(Rectangle awtRectangle) {
        return new PageData.Rect(awtRectangle.x, awtRectangle.y, awtRectangle.width, awtRectangle.height);
    }
}
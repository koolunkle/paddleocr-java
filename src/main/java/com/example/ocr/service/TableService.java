package com.example.ocr.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ocr.config.AppProperties;
import com.example.ocr.dto.PageData;
import com.example.ocr.support.MatResourceWrapper;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;

/**
 * SLANet 모델을 활용하여 표 이미지의 논리적 구조(HTML) 및 각 셀의 좌표를 추출하는 서비스
 */
@Service
public class TableService {

    // ========================================================================
    // 상수 및 상태 관리
    // ========================================================================

    private static final Logger log = LoggerFactory.getLogger(TableService.class);
    
    /** 모델 입력에 사용되는 정방형 이미지 크기 */
    private static final int TARGET_SIZE = 488;

    /** ONNX 런타임 환경 엔진 */
    private final OrtEnvironment onnxEnvironment;
    
    /** 모델 추론을 위한 세션 */
    private OrtSession onnxSession;
    
    /** 모델 토큰 인덱스와 매핑되는 사전 데이터 */
    private List<String> tableDict;

    public TableService(AppProperties appProperties, OrtEnvironment onnxEnvironment) {
        this.onnxEnvironment = onnxEnvironment;
    }


    // ========================================================================
    // 초기화 및 종료 관리
    // ========================================================================

    /**
     * 지정된 경로의 모델 파일과 사전을 로드하여 서비스를 초기화합니다.
     */
    public void init(String modelPath, String dictPath) throws Exception {
        if (this.onnxSession != null) {
            return;
        }

        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            this.onnxSession = this.onnxEnvironment.createSession(modelPath, options);
        }

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(dictPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            List<String> lines = reader.lines().collect(Collectors.toList());

            // 모델의 출력 토큰 구조에 맞춰 사전 데이터를 정제하고 인덱스를 정렬합니다.
            if (lines.contains("<td>")) {
                lines.remove("<td>");
                if (!lines.contains("<td></td>")) {
                    lines.add("<td></td>");
                }
            }

            this.tableDict = new ArrayList<>();
            this.tableDict.add("sos");
            this.tableDict.addAll(lines);
            this.tableDict.add("eos");
        }

        log.info("Table model initialized (Vocab size: {})", this.tableDict.size());
    }

    /**
     * 애플리케이션 종료 시 로드된 ONNX 세션 자원을 해제합니다.
     */
    @PreDestroy
    public void close() {
        if (this.onnxSession != null) {
            try {
                this.onnxSession.close();
            } catch (Exception e) {
                log.warn("Error closing ONNX session", e);
            }
        }
    }


    // ========================================================================
    // 표 구조 분석 API
    // ========================================================================

    /**
     * 표 영역 이미지를 분석하여 논리적 구조와 셀 좌표가 포함된 데이터를 반환합니다.
     */
    public PageData.TableStructure analyze(Mat tableImage) {
        if (this.onnxSession == null || tableImage == null || tableImage.empty()) {
            return null;
        }

        try (MatResourceWrapper wrapper = new MatResourceWrapper()) {
            int h = tableImage.rows();
            int w = tableImage.cols();
            double ratio = (double) TARGET_SIZE / Math.max(h, w);
            int resizeH = (int) (h * ratio);
            int resizeW = (int) (w * ratio);

            Mat rgbImg = wrapper.add(new Mat());
            Imgproc.cvtColor(tableImage, rgbImg, Imgproc.COLOR_BGR2RGB);

            Mat resized = wrapper.add(new Mat());
            Imgproc.resize(rgbImg, resized, new Size(resizeW, resizeH));

            float[] inputData = preprocess(resized, resizeH, resizeW);

            long[] shape = {1, 3, TARGET_SIZE, TARGET_SIZE};
            String inputName = this.onnxSession.getInputNames().iterator().next();

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(this.onnxEnvironment, FloatBuffer.wrap(inputData), shape);
                 OrtSession.Result result = this.onnxSession.run(Collections.singletonMap(inputName, inputTensor))) {

                return decodeModelOutput(result, ratio);
            }
        } catch (Exception e) {
            log.error("Table structure analysis failed", e);
            return null;
        }
    }


    // ========================================================================
    // 모델 출력 디코딩 및 후처리
    // ========================================================================

    /**
     * 모델의 추론 결과 텐서에서 구조 확률과 좌표 정보를 추출합니다.
     */
    private PageData.TableStructure decodeModelOutput(OrtSession.Result result, double ratio) {
        float[] structureProb = null;
        float[] cellCoords = null;
        int seqLen = 0;
        int coordDim = 0;

        for (Map.Entry<String, ai.onnxruntime.OnnxValue> entry : result) {
            OnnxTensor tensor = (OnnxTensor) entry.getValue();
            long[] shape = tensor.getInfo().getShape();

            if (shape.length == 3 && shape[2] >= 20) {
                structureProb = new float[(int) (shape[1] * shape[2])];
                tensor.getFloatBuffer().get(structureProb);
                seqLen = (int) shape[1];
            } else if (shape.length == 3 && (shape[2] == 4 || shape[2] == 8)) {
                cellCoords = new float[(int) (shape[1] * shape[2])];
                tensor.getFloatBuffer().get(cellCoords);
                coordDim = (int) shape[2];
            }
        }

        if (structureProb == null || cellCoords == null) {
            return null;
        }

        return postProcessRobust(structureProb, cellCoords, seqLen, coordDim, ratio);
    }

    /**
     * 토큰 시퀀스와 좌표 데이터를 결합하여 표의 논리적 그리드를 복원합니다.
     */
    private PageData.TableStructure postProcessRobust(float[] prob, float[] coords, int seqLen, int coordDim, double ratio) {
        int vocabSize = prob.length / seqLen;
        List<String> tags = new ArrayList<>();
        List<PageData.Rect> boxes = new ArrayList<>();

        // 1. 토큰 확률 배열에서 가장 높은 가능성을 가진 태그와 해당 시점의 좌표 시퀀스 추출
        for (int i = 0; i < seqLen; i++) {
            int argmax = 0;
            float maxP = -Float.MAX_VALUE;
            for (int v = 0; v < vocabSize; v++) {
                float p = prob[i * vocabSize + v];
                if (p > maxP) {
                    maxP = p;
                    argmax = v;
                }
            }

            if (argmax >= this.tableDict.size()) {
                continue;
            }

            String token = this.tableDict.get(argmax);
            if (token.equals("eos")) {
                break;
            }
            if (token.equals("sos")) {
                continue;
            }

            tags.add(token);

            // 셀 정의 태그인 경우에만 물리적 좌표를 수집하여 데이터 정합성 유지
            if (token.equals("<td></td>") || token.equals("<td")) {
                boxes.add(decodeRect(coords, i * coordDim, coordDim, ratio));
            }
        }

        // 2. 논리적 그리드 점유(Occupied) 알고리즘을 통한 표 구조 복원
        List<PageData.Cell> headerCells = new ArrayList<>();
        List<PageData.Cell> bodyCells = new ArrayList<>();
        Map<String, Boolean> occupied = new HashMap<>();

        int currentRow = 0;
        int currentCol = 0;
        int boxIdx = 0;
        boolean inHeader = false;

        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);

            if (tag.equals("<thead>")) {
                inHeader = true;
            } else if (tag.equals("</thead>") || tag.equals("<tbody>")) {
                inHeader = false; 
            } else if (tag.equals("<tr>")) {
                currentCol = 0;
            } else if (tag.equals("</tr>")) {
                currentRow++;
            } else if (tag.equals("<td></td>") || tag.equals("<td")) {
                int cs = 1, rs = 1;
                int tagIdx = i;

                // 병합 정보(colspan, rowspan) 속성 파싱
                if (!tag.equals("<td></td>")) {
                    tagIdx++;
                    while (tagIdx < tags.size() && !tags.get(tagIdx).contains(">")) {
                        String attr = tags.get(tagIdx);
                        if (attr.contains("colspan")) {
                            cs = Integer.parseInt(attr.replaceAll("[^0-9]", ""));
                        } else if (attr.contains("rowspan")) {
                            rs = Integer.parseInt(attr.replaceAll("[^0-9]", ""));
                        }
                        tagIdx++;
                    }
                }
                i = tagIdx;

                // 현재 행에서 비어있는 논리적 위치 탐색
                while (occupied.containsKey(currentRow + "," + currentCol)) {
                    currentCol++;
                }

                PageData.Rect rect = (boxIdx < boxes.size()) ? boxes.get(boxIdx++) : new PageData.Rect(0, 0, 0, 0);
                PageData.Cell cell = new PageData.Cell("", currentRow + 1, currentCol + 1, cs, rs, rect);

                if (inHeader) {
                    headerCells.add(cell);
                } else {
                    bodyCells.add(cell);
                }

                // 병합 영역에 대해 그리드 점유 상태 기록
                for (int r = 0; r < rs; r++) {
                    for (int c = 0; c < cs; c++) {
                        occupied.put((currentRow + r) + "," + (currentCol + c), true);
                    }
                }
                currentCol += cs;
            }
        }

        return buildFinalStructure(headerCells, bodyCells);
    }


    // ========================================================================
    // 유틸리티 메서드
    // ========================================================================

    /**
     * 복원된 셀들을 행별로 정렬하고 헤더와 바디 영역을 최종 확정합니다.
     */
    private PageData.TableStructure buildFinalStructure(List<PageData.Cell> headerCells, List<PageData.Cell> bodyCells) {
        Map<Integer, List<PageData.Cell>> rowsMap = new TreeMap<>();
        headerCells.forEach(c -> rowsMap.computeIfAbsent(c.row(), k -> new ArrayList<>()).add(c));
        bodyCells.forEach(c -> rowsMap.computeIfAbsent(c.row(), k -> new ArrayList<>()).add(c));

        List<PageData.Row> allRows = new ArrayList<>();
        for (List<PageData.Cell> rowCells : rowsMap.values()) {
            rowCells.sort(Comparator.comparingInt(PageData.Cell::col));
            allRows.add(new PageData.Row(rowCells));
        }

        // 헤더 영역 결정 (모델 태그 우선, 없을 시 최상단 행 기준 추정)
        int headerRowLimit = 0;
        if (!headerCells.isEmpty()) {
            headerRowLimit = headerCells.stream()
                    .mapToInt(c -> c.row() + (c.rowspan() != null ? c.rowspan() - 1 : 0))
                    .max().orElse(0);
        } else if (!allRows.isEmpty()) {
            headerRowLimit = allRows.get(0).cells().stream()
                    .mapToInt(c -> (c.rowspan() != null ? c.rowspan() : 1))
                    .max().orElse(1);
        }

        List<PageData.Cell> finalHeader = new ArrayList<>();
        List<PageData.Row> finalRows = new ArrayList<>();

        for (int i = 0; i < allRows.size(); i++) {
            if (i + 1 <= headerRowLimit) {
                finalHeader.addAll(allRows.get(i).cells());
            } else {
                finalRows.add(allRows.get(i));
            }
        }

        log.info("Table reconstruction complete: {} header cells, {} body rows", finalHeader.size(), finalRows.size());
        return new PageData.TableStructure(finalHeader, finalRows);
    }

    /**
     * 이미지를 정규화하고 모델 입력 크기에 맞춰 패딩을 적용한 부동소수점 배열로 변환합니다.
     */
    private float[] preprocess(Mat resized, int h, int w) {
        float[] data = new float[3 * TARGET_SIZE * TARGET_SIZE];
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};

        Mat fMat = new Mat();
        resized.convertTo(fMat, CvType.CV_32FC3, 1.0 / 255.0);
        float[] pixels = new float[h * w * 3];
        fMat.get(0, 0, pixels);

        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < h; r++) {
                for (int col = 0; col < w; col++) {
                    int src = (r * w + col) * 3 + c;
                    int dst = c * TARGET_SIZE * TARGET_SIZE + r * TARGET_SIZE + col;
                    data[dst] = (pixels[src] - mean[c]) / std[c];
                }
            }
        }
        return data;
    }

    /**
     * 모델의 정규화된 출력 좌표를 원본 이미지 스케일에 맞춘 사각형 영역으로 변환합니다.
     */
    private PageData.Rect decodeRect(float[] coords, int offset, int dim, double ratio) {
        float x1, y1, x2, y2;
        if (dim == 8) {
            x1 = getMin(coords[offset], coords[offset + 2], coords[offset + 4], coords[offset + 6]);
            y1 = getMin(coords[offset + 1], coords[offset + 3], coords[offset + 5], coords[offset + 7]);
            x2 = getMax(coords[offset], coords[offset + 2], coords[offset + 4], coords[offset + 6]);
            y2 = getMax(coords[offset + 1], coords[offset + 3], coords[offset + 5], coords[offset + 7]);
        } else {
            x1 = coords[offset];
            y1 = coords[offset + 1];
            x2 = coords[offset + 2];
            y2 = coords[offset + 3];
        }

        int rx = (int) Math.floor((x1 * (double) TARGET_SIZE) / ratio);
        int ry = (int) Math.floor((y1 * (double) TARGET_SIZE) / ratio);
        int rw = (int) Math.ceil(((x2 - x1) * (double) TARGET_SIZE) / ratio);
        int rh = (int) Math.ceil(((y2 - y1) * (double) TARGET_SIZE) / ratio);

        return new PageData.Rect(Math.max(0, rx), Math.max(0, ry), Math.max(1, rw), Math.max(1, rh));
    }

    /** 다수 좌표값 중 최소값 탐색 */
    private float getMin(float... v) {
        float m = v[0];
        for (float x : v) {
            if (x < m) {
                m = x;
            }
        }
        return m;
    }

    /** 다수 좌표값 중 최대값 탐색 */
    private float getMax(float... v) {
        float m = v[0];
        for (float x : v) {
            if (x > m) {
                m = x;
            }
        }
        return m;
    }
}

package io.github.mymonstercat;

import java.util.stream.Stream;

/**
 * RapidOCR-Java 라이브러리의 원본 Model Enum을 섀도잉(Shadowing)한 클래스입니다.
 * 원본 라이브러리가 기본 중국어/영어 모델만 바라보는 제약을 우회하고, 
 * 한국어 특화 모델 및 사전(Dictionary) 파일을 로드하기 위해 원본과 동일한 패키지 경로에 작성되었습니다.
 */
public enum Model {

    // ========================================================================
    // 정의된 모델 세트 
    // ========================================================================

    /** PP-OCR v3 (기본 중국어/영어 모델) */
    ONNX_PPOCR_V3(
            "onnx", 
            "models/onnx", 
            "rapidocr", 
            "ch_PP-OCRv3_det_infer.onnx",
            "ch_PP-OCRv3_rec_infer.onnx", 
            "ch_ppocr_mobile_v2.0_cls_infer.onnx",
            "ppocr_keys_v1.txt"
    ),

    /** PP-OCR v4 (한국어 인식 특화 모델 및 사전 적용) */
    ONNX_PPOCR_V4(
            "onnx", 
            "models/onnx", 
            "rapidocr", 
            "ch_PP-OCRv4_det_infer.onnx",          // [Det] 텍스트 영역 탐지 모델
            "korean_PP-OCRv4_rec_infer.onnx",      // [Rec] 한국어 텍스트 인식 모델
            "ch_ppocr_mobile_v2.0_cls_infer.onnx", // [Cls] 텍스트 방향(180도 회전 등) 분류 모델
            "korean_dict.txt"                      // [Dict] 한국어 매핑 문자 사전
    );

    // ========================================================================
    // 상태 변수 
    // ========================================================================

    private final String modelType;
    private final String modelsDir;
    private final String tempDirPath;
    private final String detName;
    private final String recName;
    private final String clsName;
    private final String keysName;

    // ========================================================================
    // 생성자
    // ========================================================================

    Model(String modelType, String modelsDir, String tempDirPath, 
          String detName, String recName, String clsName, String keysName) {
          
        this.modelType = modelType;
        this.modelsDir = modelsDir;
        this.tempDirPath = tempDirPath;
        this.detName = detName;
        this.recName = recName;
        this.clsName = clsName;
        this.keysName = keysName;
    }

    // ========================================================================
    // 공개 메서드 - Getter
    // ========================================================================

    public String getModelType() { 
        return this.modelType; 
    }

    public String getModelsDir() { 
        return this.modelsDir; 
    }

    public String getTempDirPath() { 
        return this.tempDirPath; 
    }

    public String getDetName() { 
        return this.detName; 
    }

    public String getRecName() { 
        return this.recName; 
    }

    public String getClsName() { 
        return this.clsName; 
    }

    public String getKeysName() { 
        return this.keysName; 
    }

    /**
     * 설정된 모델 및 사전 파일들의 이름을 배열 형태로 묶어 반환합니다.
     * 빈 값이나 null인 파일명은 안전하게 필터링됩니다.
     */
    public String[] getModelFileArray() {
        return Stream.of(this.detName, this.recName, this.clsName, this.keysName)
                .filter(name -> name != null && !name.isEmpty())
                .toArray(String[]::new);
    }
}
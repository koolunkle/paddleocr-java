package com.example.ocr.support;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Mat;

/**
 * OpenCV Mat 객체의 생명주기를 관리하는 AutoCloseable 래퍼 클래스입니다.
 * try-with-resources 블록과 함께 사용하여 네이티브 메모리 누수(Memory Leak)를 원천 차단합니다.
 */
public class MatResourceWrapper implements AutoCloseable {

    private final List<Mat> trackedMats = new ArrayList<>();

    /**
     * 안전하게 해제할 Mat 객체를 등록합니다.
     * 체이닝(Chaining)을 지원하여 객체 생성과 동시에 등록할 수 있습니다.
     *
     * @param mat 등록할 OpenCV Mat 객체
     * @return 매개변수로 전달된 Mat 객체 (그대로 반환)
     */
    public Mat add(Mat mat) {
        if (mat != null) {
            this.trackedMats.add(mat);
        }
        return mat;
    }

    /**
     * try 블록이 종료될 때 자동으로 호출되어, 
     * 등록된 모든 Mat 객체의 네이티브 메모리를 안전하게 해제합니다.
     */
    @Override
    public void close() {
        for (Mat mat : this.trackedMats) {
            if (mat != null) {
                mat.release();
            }
        }
        this.trackedMats.clear();
    }
}
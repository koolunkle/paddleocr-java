package com.example.ocr.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import com.example.ocr.config.AppConfig;
import com.example.ocr.config.AppProperties;
import com.example.ocr.dto.AnalysisResponse;
import com.example.ocr.service.ProcessorService;

// AppConfig 로드 제외 (CORS NPE 방지)
@WebMvcTest(
        controllers = DocumentController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AppConfig.class)
)
@DisplayName("DocumentController API 테스트")
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // Controller가 의존하는 서비스를 Mock(가짜) 객체로 주입 (실제 AI 모델은 동작하지 않음)
    @MockBean
    private ProcessorService processorService;

    // DocumentController에서 SSE 통신을 위해 주입받는 스레드 풀 가짜 객체
    @MockBean(name = "sseExecutor")
    private AsyncTaskExecutor sseTaskExecutor;

    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    private AppProperties appProperties;

    @Test
    @DisplayName("동기화 OCR 분석 API (POST /api/v1/ocr) 호출 성공 테스트")
    void uploadDocument_sync_success() throws Exception {
        // 1. Given (준비)
        // 가짜 업로드 파일 생성
        MockMultipartFile mockFile = new MockMultipartFile(
                "file",                          // 파라미터 이름
                "test-document.tif", // 원본 파일명
                "image/tiff",
                "dummy image content".getBytes()
        );

        // ProcessorService의 process() 메서드가 호출될 때 반환할 가짜 응답 정의
        AnalysisResponse mockResponse = new AnalysisResponse("test-document.png", List.of());

        given(processorService.process(any(File.class), anyList(), anyString()))
                .willReturn(mockResponse);

        // 2. When & 3. Then (실행 및 검증)
        mockMvc.perform(
                multipart("/api/v1/ocr")
                        .file(mockFile)
                        .param("pages", "1,2") // 선택적 파라미터
                        .accept(MediaType.APPLICATION_JSON)
        )
        .andDo(print())
        .andExpect(status().isOk()) // HTTP 200 OK 검증
        .andExpect(jsonPath("$.fileName").value("test-document.png")) // JSON 응답 검증
        .andExpect(jsonPath("$.results").isArray());
    }
}

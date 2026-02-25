package com.example.ocr.config;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.ocr.constant.AppConstants;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

/**
 * 애플리케이션 전반의 공통 인프라 설정을 담당하는 Configuration 클래스입니다.
 * Web (CORS 정책), 비동기 처리 (Async 스레드 풀), API 문서화 (Swagger/OpenAPI) 설정을 포함합니다.
 */
@Configuration
@EnableAsync
@ConfigurationPropertiesScan(basePackageClasses = AppProperties.class)
public class AppConfig implements WebMvcConfigurer {

    // ========================================================================
    // 상수 
    // ========================================================================

    /** CORS 설정을 적용할 전체 API 경로 패턴 */
    private static final String ALL_API_PATH_PATTERN = "/**";

    // ========================================================================
    // 상태 변수 
    // ========================================================================

    private final AppProperties appProperties;

    // ========================================================================
    // 생성자 
    // ========================================================================

    public AppConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // ========================================================================
    // WebMvcConfigurer 설정
    // ========================================================================

    /**
     * 프론트엔드 등 외부 도메인에서의 API 호출을 허용하기 위해 CORS 정책을 설정합니다.
     */
    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        AppProperties.Cors corsProperties = this.appProperties.cors();

        registry.addMapping(ALL_API_PATH_PATTERN)
                .allowedOriginPatterns(Objects.requireNonNull(corsProperties.allowedOriginPatterns().toArray(new String[0])))
                .allowedMethods(Objects.requireNonNull(corsProperties.allowedMethods().toArray(new String[0])))
                .allowedHeaders(Objects.requireNonNull(corsProperties.allowedHeaders().toArray(new String[0])))
                .allowCredentials(corsProperties.allowCredentials())
                .maxAge(corsProperties.maxAge());
    }

    // ========================================================================
    // Spring Bean 설정 
    // ========================================================================

    /**
     * SSE(Server-Sent Events) 스트리밍 응답 및 비동기 추론 작업을 처리하기 위한 전용 스레드 풀을 구성합니다.
     *
     * @return 초기화된 비동기 태스크 실행기 (AsyncTaskExecutor)
     */
    @Bean(name = "sseExecutor")
    AsyncTaskExecutor sseTaskExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        AppProperties.Async asyncProperties = this.appProperties.async();

        taskExecutor.setCorePoolSize(asyncProperties.corePoolSize());
        taskExecutor.setMaxPoolSize(asyncProperties.maxPoolSize());
        taskExecutor.setQueueCapacity(asyncProperties.queueCapacity());
        taskExecutor.setKeepAliveSeconds(asyncProperties.keepAliveSeconds());
        taskExecutor.setThreadNamePrefix(AppConstants.Sse.THREAD_PREFIX);
        
        // 큐가 가득 찼을 때의 거절 정책: 예외(RejectedExecutionException)를 발생시켜 클라이언트에게 즉각적인 실패를 알림 (Fail-fast)
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        
        // 안전한 종료(Graceful Shutdown)를 위한 설정: JVM 종료 시 큐에 남은 작업을 마칠 수 있도록 대기
        taskExecutor.setWaitForTasksToCompleteOnShutdown(asyncProperties.waitForTasksOnShutdown());
        taskExecutor.setAwaitTerminationSeconds(asyncProperties.shutdownTimeoutSeconds());
        
        taskExecutor.initialize();

        return taskExecutor;
    }

    /**
     * Swagger(SpringDoc)를 이용한 OpenAPI 명세서 기본 정보를 설정합니다.
     */
    @Bean
    OpenAPI customOpenApiDocs() {
        AppProperties.Documents docsProperties = this.appProperties.documents();

        Info apiInformation = new Info()
                .title(docsProperties.title())
                .version(docsProperties.version())
                .description(docsProperties.description());

        Server localServer = new Server()
                .url(docsProperties.localUrl())
                .description(docsProperties.localDescription());
                
        Server currentServer = new Server()
                .url(docsProperties.currentUrl())
                .description(docsProperties.currentDescription());

        return new OpenAPI()
                .info(apiInformation)
                .servers(List.of(localServer, currentServer));
    }
}
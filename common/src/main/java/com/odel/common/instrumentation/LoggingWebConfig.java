package com.odel.common.instrumentation;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Every API call gets its request log line (LoggingInterceptor); health checks and Swagger are left out. */
@Configuration(proxyBeanMethods = false)
public class LoggingWebConfig implements WebMvcConfigurer {
    private final LoggingInterceptor loggingInterceptor;

    public LoggingWebConfig(LoggingInterceptor loggingInterceptor) {
        this.loggingInterceptor = loggingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loggingInterceptor)
                .excludePathPatterns("/actuator/**", "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**");
    }
}

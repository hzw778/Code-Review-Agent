package com.codereview.agent.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 全局 CORS 配置
 *
 * <p>使用 CorsFilter（最高优先级）替代 WebMvcConfigurer，
 * 确保 OPTIONS 预检请求在 Spring Security / 其他 Filter 之前被处理。
 *
 * <p>关键点：
 * <ul>
 *   <li>allowedOriginPatterns("*") + allowCredentials(true) 兼容所有来源</li>
 *   <li>显式允许 OPTIONS 方法（preflight 请求）</li>
 *   <li>注册到 HIGHEST_PRECEDENCE，避免被其他 Filter 短路</li>
 * </ul>
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        // 用 allowedOriginPatterns 而非 allowedOrigins，配合 allowCredentials=true 时不会报错
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}

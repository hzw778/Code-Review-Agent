package com.codereview.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Code Review Agent 启动类
 * <p>
 * 作用：
 * 1. 标记为 Spring Boot 应用入口
 * 2. 自动扫描 com.codereview.agent 包及其子包下的所有组件
 * 3. 启用 Spring Boot 自动配置（根据依赖自动装配 Bean）
 * </p>
 *
 * @author CodeReviewAgent
 * @date 2026-06-29
 */
@SpringBootApplication
public class CodeReviewAgentApplication {

    public static void main(String[] args) {
        // 启动 Spring 容器 + 内嵌 Tomcat
        SpringApplication.run(CodeReviewAgentApplication.class, args);
    }
}

package com.codereview.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;


@SpringBootApplication
@EnableAsync
public class CodeReviewAgentApplication {

    public static void main(String[] args) {
        // 启动 Spring 容器 + 内嵌 Tomcat
        SpringApplication.run(CodeReviewAgentApplication.class, args);
    }
}

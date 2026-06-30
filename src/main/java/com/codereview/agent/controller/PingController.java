package com.codereview.agent.controller;

import com.codereview.agent.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 测试接口控制器
 * <p>
 * 提供两个接口：
 * 1. GET /ping        - 同步调用，返回完整响应（等待 LLM 全部输出）
 * 2. GET /ping/stream - 流式调用，SSE 实时推送 token（边生成边返回）
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/ping")
@RequiredArgsConstructor
public class PingController {

    private final LlmService llmService;



    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> pingStream(@RequestParam(defaultValue = "你好，请用一句话介绍你自己") String message) {
        log.info("[接口入口] GET /ping/stream (SSE流式) - message={}", message);
        return llmService.chat(message)
                .doOnComplete(() -> log.info("[接口出口] GET /ping/stream 流式响应完成"));
    }
}
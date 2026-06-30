package com.codereview.agent.service.impl;

import com.codereview.agent.exception.BusinessException;
import com.codereview.agent.exception.ErrorCode;
import com.codereview.agent.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
@Slf4j
@Service
public class LlmServiceImpl implements LlmService {

    private final ChatClient chatClient;

    public LlmServiceImpl(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Flux<String> chat(String userInput) {
        log.info("[LLM调用] 流式调用LLM, 用户输入长度={}, 输入预览={}",
                userInput.length(),
                userInput.length() > 50 ? userInput.substring(0, 50) + "..." : userInput);

        long startTime = System.currentTimeMillis();

        // 流式调用：.stream() 返回 Flux<String>，每个元素是一个 token 片段
        // doOnComplete 记录完成日志，doOnError 记录异常日志
        return chatClient.prompt()
                .user(userInput)
                .stream()
                .content()
                .doOnComplete(() -> {
                    long costMs = System.currentTimeMillis() - startTime;
                    log.info("[LLM调用] 流式响应完成, 耗时={}ms", costMs);
                })
                .doOnError(e -> {
                    long costMs = System.currentTimeMillis() - startTime;
                    log.error("[异常] LLM流式调用失败, 耗时={}ms, 错误={}", costMs, e.getMessage(), e);
                })
                .onErrorResume(e -> Flux.error(
                        new BusinessException(ErrorCode.LLM_CALL_FAILED, "LLM 调用失败: " + e.getMessage())
                ));
    }
}
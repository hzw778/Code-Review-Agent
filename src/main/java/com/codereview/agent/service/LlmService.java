package com.codereview.agent.service;

import reactor.core.publisher.Flux;

public interface LlmService {

    /**
     * 调用 LLM 进行单轮对话
     *
     * @param userInput 用户输入文本
     * @return LLM 响应文本
     */
    Flux<String> chat(String userInput);

}

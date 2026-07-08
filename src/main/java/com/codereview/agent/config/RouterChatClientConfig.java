package com.codereview.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Router 专用 ChatClient 配置
 *
 * <p>独立构建一个使用 qwen-flash 轻量模型的 ChatClient，
 * 与默认的 glm-4.5-air（流式）ChatClient 隔离。
 *
 * <p>多模型架构：
 * <ul>
 *   <li>默认 ChatClient（glm-4.5-air）：最终回复，流式</li>
 *   <li>routerChatClient（qwen-flash）：内部分类，非流式同步</li>
 * </ul>
 */
@Configuration
public class RouterChatClientConfig {

    /**
     * Router 专用 ChatClient Bean。
     *
     * <p>Bean 名为 routerChatClient，注入时用 @Qualifier("routerChatClient") 指定。
     */
    @Bean("routerChatClient")
    public ChatClient routerChatClient(AgentProperties properties) {
        AgentProperties.RouterModel routerModel = properties.getRouterModel();

        // 手动构建 OpenAiApi（指定独立的 base-url）
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(routerModel.getBaseUrl())
                .apiKey(routerModel.getApiKey())
                .completionsPath("/chat/completions")
                .build();

        // 配置轻量模型
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(routerModel.getModel())
                .temperature(0.3)
                .build();

        // 构建 ChatModel
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .build();

        // 构建 ChatClient
        return ChatClient.builder(chatModel).build();
    }
}

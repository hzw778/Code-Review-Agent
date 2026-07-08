package com.codereview.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 聊天 HTTP 接口（SSE 流式）
 *
 * <p>POST /chat/stream：用户输入 → Router 分类 → 流式返回
 *
 * <p>SSE 事件格式（每个 event 一个 JSON 字符串）：
 * <ul>
 *   <li>META：{type, routerType, routerCostMs, references, trace} — 前端先渲染元数据</li>
 *   <li>TOKEN：{type, data} — LLM 流式 token，前端拼接显示打字机效果</li>
 *   <li>DONE：{type, totalCostMs} — 流结束</li>
 *   <li>ERROR：{type, data} — 错误信息</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 流式聊天（SSE）
     *
     * <p>Content-Type: text/event-stream
     * <p>每个事件格式：data: {JSON}\n\n
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody ChatRequest request) {
        log.info("[ChatController] 收到流式消息, 长度={}", request.getMessage().length());
        return chatService.streamChat(request.getMessage())
                .map(this::serializeEvent)
                .onErrorResume(e -> {
                    log.error("[ChatController] 流式异常", e);
                    return Flux.just(safeJson(Map.of("type", "ERROR", "data", e.getMessage())));
                });
    }

    /** 把 ChatStreamEvent 序列化为 SSE 数据行 */
    private String serializeEvent(ChatService.ChatStreamEvent ev) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", ev.type().name());
        if (ev.data() != null) m.put("data", ev.data());
        if (ev.meta() != null) m.putAll(ev.meta());
        return safeJson(m);
    }

    private String safeJson(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"type\":\"ERROR\",\"data\":\"序列化失败\"}";
        }
    }
}

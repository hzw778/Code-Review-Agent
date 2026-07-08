package com.codereview.agent.agent;

import com.codereview.agent.model.ApiResponse;
import com.codereview.agent.repository.entity.ChatMessage;
import com.codereview.agent.repository.entity.ChatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天 HTTP 接口（SSE 流式 + 会话管理）
 *
 * <p>POST /chat/stream：用户输入 → Router 分类 → 流式返回（带多轮记忆）
 * <p>GET  /chat/sessions：会话列表
 * <p>GET  /chat/sessions/{sessionId}/messages：会话消息列表
 * <p>DELETE /chat/sessions/{sessionId}：删除会话
 *
 * <p>SSE 事件格式（每个 event 一个 JSON 字符串）：
 * <ul>
 *   <li>META：{type, sessionId, routerType, routerCostMs, references, trace}</li>
 *   <li>TOKEN：{type, data}</li>
 *   <li>DONE：{type, totalCostMs}</li>
 *   <li>ERROR：{type, data}</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 流式聊天（SSE）
     *
     * <p>Content-Type: text/event-stream
     * <p>每个事件格式：data: {JSON}\n\n
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody ChatRequest request) {
        log.info("[ChatController] 收到流式消息, 长度={}, sessionId={}",
                request.getMessage().length(), request.getSessionId());
        return chatService.streamChat(request.getMessage(), request.getSessionId())
                .map(this::serializeEvent)
                .onErrorResume(e -> {
                    log.error("[ChatController] 流式异常", e);
                    return Flux.just(safeJson(Map.of("type", "ERROR", "data", e.getMessage())));
                });
    }

    /**
     * 会话列表（按更新时间倒序）
     */
    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> listSessions() {
        List<ChatSession> sessions = chatSessionService.listSessions();
        List<Map<String, Object>> data = new ArrayList<>();
        for (ChatSession s : sessions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("title", s.getTitle());
            m.put("messageCount", s.getMessageCount());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
            data.add(m);
        }
        return ApiResponse.success(data);
    }

    /**
     * 会话消息列表（按时间正序，用于历史回看）
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<Map<String, Object>>> getMessages(@PathVariable String sessionId) {
        if (chatSessionService.getSession(sessionId).isEmpty()) {
            return ApiResponse.error(404, "会话不存在: " + sessionId);
        }
        List<ChatMessage> messages = chatSessionService.getMessages(sessionId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (ChatMessage m : messages) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("id", m.getId());
            msg.put("role", m.getRole());
            msg.put("content", m.getContent());
            msg.put("routerType", m.getRouterType());
            msg.put("references", m.getReferences());
            msg.put("costMs", m.getCostMs());
            msg.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
            data.add(msg);
        }
        return ApiResponse.success(data);
    }

    /**
     * 删除会话（同时删除所有消息 + 清理 Redis 缓存）
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        boolean deleted = chatSessionService.deleteSession(sessionId);
        if (!deleted) {
            return ApiResponse.error(404, "会话不存在: " + sessionId);
        }
        return ApiResponse.success(null);
    }

    /** 把 ChatStreamEvent 序列化为 SSE 数据行 */
    private String serializeEvent(ChatService.ChatStreamEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
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

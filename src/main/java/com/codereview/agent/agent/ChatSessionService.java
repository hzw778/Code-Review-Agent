package com.codereview.agent.agent;

import com.codereview.agent.repository.ChatMessageRepository;
import com.codereview.agent.repository.ChatSessionRepository;
import com.codereview.agent.repository.entity.ChatMessage;
import com.codereview.agent.repository.entity.ChatSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 聊天会话服务
 *
 * <p>负责会话生命周期管理（创建/查询/删除）与全量消息持久化（MySQL）。
 *
 * <p>多轮记忆的"热数据"读取由 ChatMemoryService 负责（Redis 滑动窗口），
 * 本服务只负责 MySQL 全量存储（会话回看/审计用）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>消息保存（双写 Redis+MySQL）已移至 ChatMemoryService</li>
 *   <li>本类仅提供 incrementMessageCount 更新会话统计</li>
 *   <li>删除会话时联动清理 Redis 缓存（通过 ChatMemoryService.evictSession）</li>
 *   <li>getMessages 仍从 MySQL 读取全量，用于历史回看</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatMemoryService chatMemoryService;

    /** 标题最大长度 */
    private static final int TITLE_MAX_LENGTH = 20;

    /**
     * 创建新会话。
     *
     * @param firstMessage 首条用户消息（用于生成标题）
     * @return 新创建的会话
     */
    @Transactional
    public ChatSession createSession(String firstMessage) {
        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setTitle(generateTitle(firstMessage));
        session.setMessageCount(0);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    /**
     * 获取会话详情。
     */
    @Transactional(readOnly = true)
    public Optional<ChatSession> getSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    /**
     * 历史会话列表（按更新时间倒序）。
     */
    @Transactional(readOnly = true)
    public List<ChatSession> listSessions() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc();
    }

    /**
     * 加载某会话的所有消息（按时间正序）。
     * 用于前端历史回看，从 MySQL 读取全量。
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(String sessionId) {
        return messageRepository.findBySessionIdStrOrderByCreatedAtAsc(sessionId);
    }

    /**
     * 更新会话统计（消息数 + 更新时间）。
     * 由 ChatMemoryService.saveMessage 完成双写后调用。
     */
    @Transactional
    public void incrementMessageCount(String sessionId) {
        sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            s.setMessageCount((s.getMessageCount() == null ? 0 : s.getMessageCount()) + 1);
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        });
    }

    /**
     * 删除会话（同时删除 MySQL 消息 + 清理 Redis 缓存）。
     */
    @Transactional
    public boolean deleteSession(String sessionId) {
        if (!sessionRepository.existsBySessionId(sessionId)) {
            return false;
        }
        // 1. 删 MySQL 消息
        messageRepository.deleteBySessionIdStr(sessionId);
        // 2. 删 MySQL 会话
        sessionRepository.deleteBySessionId(sessionId);
        // 3. 清 Redis 缓存
        chatMemoryService.evictSession(sessionId);
        log.info("[ChatSessionService] 删除会话, sessionId={}", sessionId);
        return true;
    }

    /**
     * 由首条用户消息生成会话标题。
     * 取前 20 个字符，超长加省略号。
     */
    private String generateTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return "新对话";
        }
        String trimmed = firstMessage.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= TITLE_MAX_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, TITLE_MAX_LENGTH) + "…";
    }
}

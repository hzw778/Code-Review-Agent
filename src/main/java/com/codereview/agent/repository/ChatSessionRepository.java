package com.codereview.agent.repository;

import com.codereview.agent.repository.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 聊天会话 Repository
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /** 根据 sessionId 查询会话 */
    Optional<ChatSession> findBySessionId(String sessionId);

    /** 判断 sessionId 是否存在 */
    boolean existsBySessionId(String sessionId);

    /** 根据 sessionId 删除会话 */
    void deleteBySessionId(String sessionId);

    /** 历史会话列表：按更新时间倒序 */
    List<ChatSession> findAllByOrderByUpdatedAtDesc();
}

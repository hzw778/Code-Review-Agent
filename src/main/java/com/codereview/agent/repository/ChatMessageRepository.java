package com.codereview.agent.repository;

import com.codereview.agent.repository.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 聊天消息 Repository
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 根据 sessionId 查询所有消息（按时间正序，用于多轮记忆和历史回看） */
    List<ChatMessage> findBySessionIdStrOrderByCreatedAtAsc(String sessionIdStr);

    /** 删除某会话的所有消息（删除会话时先清消息） */
    void deleteBySessionIdStr(String sessionIdStr);

    /** 统计某会话的消息数 */
    long countBySessionIdStr(String sessionIdStr);
}

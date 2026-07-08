package com.codereview.agent.repository.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能问答会话实体类
 *
 * <p>对应数据库表 chat_session，存储一次对话会话的元信息。
 * 一个会话包含多轮对话（ChatMessage），支持多轮记忆与历史回看。
 *
 * <p>设计要点：
 * <ul>
 *   <li>sessionId 唯一索引：前端用 sessionId 标识会话，localStorage 持久化</li>
 *   <li>title 由首条用户消息截断生成，便于会话列表展示</li>
 *   <li>messageCount 冗余字段：列表页无需 count join 即可展示消息数</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_session",
        indexes = {@Index(name = "idx_chat_session_id", columnList = "session_id", unique = true)})
public class ChatSession {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话唯一 ID（UUID，前端用此字段标识会话） */
    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    /** 会话标题（首条用户消息前 20 字符） */
    @Column(length = 100)
    private String title;

    /** 消息总数（冗余字段，列表展示用） */
    @Column(name = "message_count")
    private Integer messageCount;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 最后更新时间（每次新增消息时更新） */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

package com.codereview.agent.repository.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 智能问答消息实体类
 *
 * <p>对应数据库表 chat_message，存储会话中的每一条消息（user 或 assistant）。
 * 与 ChatSession 一对多：一个会话包含 N 条消息。
 *
 * <p>设计要点：
 * <ul>
 *   <li>role 区分 user/assistant，用于多轮记忆时拼装 messages 列表</li>
 *   <li>content 用 TEXT：assistant 回复可能较长（含代码块）</li>
 *   <li>references 用 TEXT 存 JSON：RAG 检索引用，前端解析展示</li>
 *   <li>routerType 冗余字段：assistant 消息的分类结果（CHITCHAT/CODE_QA/REVIEW）</li>
 *   <li>sessionIdStr 冗余字段：避免 JPA 外键字段名冲突，便于直接按 sessionId 查消息</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_message",
        indexes = {
                @Index(name = "idx_chat_msg_session_id", columnList = "session_id_str"),
                @Index(name = "idx_chat_msg_created_at", columnList = "created_at")
        })
public class ChatMessage {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的会话（外键，Redis 序列化时忽略，避免懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @ToString.Exclude
    @JsonIgnore
    private ChatSession session;

    /** 会话 ID（冗余字段，便于不 join 直接按 sessionId 查消息） */
    @Column(name = "session_id_str", nullable = false, length = 64)
    private String sessionIdStr;

    /** 消息角色：user / assistant */
    @Column(nullable = false, length = 16)
    private String role;

    /** 消息内容 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** Router 分类结果（仅 assistant 消息有值：CHITCHAT/CODE_QA/REVIEW） */
    @Column(name = "router_type", length = 16)
    private String routerType;

    /** RAG 检索引用（JSON 字符串，仅 assistant 消息可能有值） */
    @Column(columnDefinition = "TEXT")
    private String references;

    /** 本条消息耗时（毫秒，仅 assistant 消息有值） */
    @Column(name = "cost_ms")
    private Long costMs;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

package com.codereview.agent.agent;

import com.codereview.agent.repository.ChatMessageRepository;
import com.codereview.agent.repository.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对话记忆服务（Redis + MySQL 双层存储）
 *
 * <p>架构分工：
 * <ul>
 *   <li>Redis（热数据）：滑动窗口存储最近 N 轮消息，LLM 多轮记忆专用，读取 O(1)</li>
 *   <li>MySQL（冷数据）：全量历史消息，会话回看/审计专用</li>
 * </ul>
 *
 * <p>滑动窗口实现：Redis List + LPUSH + LTRIM
 * <ul>
 *   <li>新消息 LPUSH 到 List 头部（最新在前）</li>
 *   <li>LTRIM 保留前 MAX_WINDOW_SIZE 条，旧数据自动淘汰</li>
 *   <li>LRANGE 0 -1 读取全部窗口（返回时反转回时间正序）</li>
 * </ul>
 *
 * <p>读流程（getMessagesForLlm）：
 * <ol>
 *   <li>先查 Redis 命中直接返回（毫秒级）</li>
 *   <li>Redis 未命中（首次/过期/重启）→ 从 MySQL 回填到 Redis，再返回</li>
 * </ol>
 *
 * <p>写流程（saveMessage）：
 * <ol>
 *   <li>先写 MySQL（全量持久化，保证不丢）</li>
 *   <li>再写 Redis（LPUSH + LTRIM 维护窗口）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMemoryService {

    private final RedisTemplate<String, ChatMessage> chatMemoryRedisTemplate;
    private final ChatMessageRepository messageRepository;

    /** Redis Key 前缀 */
    private static final String KEY_PREFIX = "chat:mem:";

    /** 滑动窗口大小：最近 10 轮 = 20 条消息 */
    private static final int MAX_WINDOW_SIZE = 20;

    /** Redis 缓存 TTL：7 天未活跃的会话缓存自动清除 */
    private static final long CACHE_TTL_DAYS = 7;

    /**
     * 获取最近 N 轮消息（用于 LLM 多轮记忆）。
     *
     * <p>读顺序：Redis → MySQL 回填。
     * 返回时间正序（最早在前，最新在后）。
     *
     * @param sessionId 会话 ID
     * @return 窗口内消息列表（时间正序），空列表表示无历史
     */
    public List<ChatMessage> getMessagesForLlm(String sessionId) {
        String key = key(sessionId);

        // 1. 先查 Redis
        List<ChatMessage> cached = readFromRedis(key);
        if (!cached.isEmpty()) {
            log.debug("[ChatMemory] Redis 命中, sessionId={}, 消息数={}", sessionId, cached.size());
            return cached;
        }

        // 2. Redis 未命中，从 MySQL 回填
        log.info("[ChatMemory] Redis 未命中, 从 MySQL 回填, sessionId={}", sessionId);
        List<ChatMessage> fromDb = messageRepository.findBySessionIdStrOrderByCreatedAtAsc(sessionId);

        // 3. 只取最近 MAX_WINDOW_SIZE 条回填到 Redis
        List<ChatMessage> window;
        if (fromDb.size() <= MAX_WINDOW_SIZE) {
            window = fromDb;
        } else {
            window = fromDb.subList(fromDb.size() - MAX_WINDOW_SIZE, fromDb.size());
        }
        backfillToRedis(key, window);

        return window;
    }

    /**
     * 保存消息（双写：MySQL 全量 + Redis 滑动窗口）。
     *
     * @param sessionId 会话 ID
     * @param role      消息角色（user/assistant）
     * @param content   消息内容
     * @param routerType Router 分类（仅 assistant）
     * @param references RAG 引用 JSON（仅 assistant）
     * @param costMs    耗时（仅 assistant）
     * @return 保存后的消息
     */
    public ChatMessage saveMessage(String sessionId, String role, String content,
                                   String routerType, String references, Long costMs) {
        // 1. 先写 MySQL（全量持久化）
        ChatMessage msg = new ChatMessage();
        msg.setSessionIdStr(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setRouterType(routerType);
        msg.setReferences(references);
        msg.setCostMs(costMs);
        msg.setCreatedAt(LocalDateTime.now());
        ChatMessage saved = messageRepository.save(msg);

        // 2. 再写 Redis（滑动窗口）
        String key = key(sessionId);
        pushToRedis(key, saved);

        log.debug("[ChatMemory] 双写完成, sessionId={}, role={}, Redis窗口={}条",
                sessionId, role, getRedisWindowSize(key));
        return saved;
    }

    /**
     * 保存用户消息（简化封装）。
     */
    public ChatMessage saveUserMessage(String sessionId, String content) {
        return saveMessage(sessionId, "user", content, null, null, null);
    }

    /**
     * 保存助手消息（简化封装）。
     */
    public ChatMessage saveAssistantMessage(String sessionId, String content,
                                            String routerType, String references, Long costMs) {
        return saveMessage(sessionId, "assistant", content, routerType, references, costMs);
    }

    /**
     * 会话被删除时清理 Redis 缓存。
     */
    public void evictSession(String sessionId) {
        String key = key(sessionId);
        chatMemoryRedisTemplate.delete(key);
        log.info("[ChatMemory] 清理 Redis 缓存, sessionId={}", sessionId);
    }

    // ============== 私有方法 ==============

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    /**
     * 从 Redis 读取窗口消息（最新在前，需反转回时间正序）。
     * Redis List 用 LPUSH 存（最新在头部），LRANGE 0 -1 返回最新在前。
     */
    private List<ChatMessage> readFromRedis(String key) {
        try {
            List<ChatMessage> list = chatMemoryRedisTemplate.opsForList().range(key, 0, -1);
            if (list == null || list.isEmpty()) {
                return Collections.emptyList();
            }
            // Redis 中最新在前，反转回时间正序（最早在前）
            List<ChatMessage> result = new ArrayList<>(list);
            Collections.reverse(result);
            return result;
        } catch (Exception e) {
            log.warn("[ChatMemory] Redis 读取失败, 降级到 MySQL: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 往 Redis 写入新消息（LPUSH + LTRIM 维护滑动窗口）。
     * LPUSH 到头部：最新消息在 index 0。
     */
    private void pushToRedis(String key, ChatMessage msg) {
        try {
            chatMemoryRedisTemplate.opsForList().leftPush(key, msg);
            // 保留前 MAX_WINDOW_SIZE 条（最新 N 条），旧数据自动淘汰
            chatMemoryRedisTemplate.opsForList().trim(key, 0, MAX_WINDOW_SIZE - 1);
            // 刷新 TTL
            chatMemoryRedisTemplate.expire(key, CACHE_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("[ChatMemory] Redis 写入失败, 仅 MySQL 生效: {}", e.getMessage());
        }
    }

    /**
     * 从 MySQL 回填到 Redis（批量 RPUSH，从最旧到最新）。
     */
    private void backfillToRedis(String key, List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        try {
            // 先清空旧缓存
            chatMemoryRedisTemplate.delete(key);
            // 按时间正序 RPUSH：最早的先入，最新的在后
            // 但我们的读取逻辑是 LPUSH（最新在头部），所以回填时要反过来
            // 这里用 leftPushAll 一次插入多条，左侧入队，顺序需要反转
            List<ChatMessage> reversed = new ArrayList<>(messages);
            Collections.reverse(reversed);  // 反转后最新在前，leftPushAll 保持这个顺序
            chatMemoryRedisTemplate.opsForList().leftPushAll(key, reversed);
            chatMemoryRedisTemplate.expire(key, CACHE_TTL_DAYS, TimeUnit.DAYS);
            log.info("[ChatMemory] Redis 回填完成, key={}, 消息数={}", key, messages.size());
        } catch (Exception e) {
            log.warn("[ChatMemory] Redis 回填失败, 后续读可直接走 MySQL: {}", e.getMessage());
        }
    }

    /**
     * 获取当前 Redis 窗口大小（调试/监控用）。
     */
    private long getRedisWindowSize(String key) {
        try {
            Long size = chatMemoryRedisTemplate.opsForList().size(key);
            return size != null ? size : 0;
        } catch (Exception e) {
            return -1;
        }
    }
}

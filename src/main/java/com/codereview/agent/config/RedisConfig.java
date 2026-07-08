package com.codereview.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.codereview.agent.repository.entity.ChatMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 *
 * <p>配置 RedisTemplate 的 Key/String 序列化方式：
 * <ul>
 *   <li>Key: StringRedisSerializer（可读字符串）</li>
 *   <li>Value: Jackson2JsonRedisSerializer（JSON 序列化，支持复杂对象）</li>
 * </ul>
 *
 * <p>用于对话记忆的滑动窗口存储：
 * Key 格式 chat:mem:{sessionId}，Value 为 ChatMessage JSON 列表。
 */
@Configuration
public class RedisConfig {

    /**
     * 聊天记忆 RedisTemplate
     *
     * <p>Key 用 String 序列化（便于 redis-cli 调试），
     * Value 用 JSON 序列化（ChatMessage 对象直接存取）。
     */
    @Bean
    public RedisTemplate<String, ChatMessage> chatMemoryRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, ChatMessage> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key 序列化：String
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 序列化：JSON（支持 LocalDateTime）
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.activateDefaultTyping(om.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        Jackson2JsonRedisSerializer<ChatMessage> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(om, ChatMessage.class);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}

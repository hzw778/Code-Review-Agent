package com.codereview.agent.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求
 *
 * <p>sessionId 为 null 表示新会话，后端创建后通过 META 事件回传给前端；
 * 非空表示已有会话，后端加载历史消息做多轮记忆。
 */
@Data
public class ChatRequest {

    /** 用户输入 */
    @NotBlank(message = "输入不能为空")
    private String message;

    /** 会话 ID（可选，null 表示新建会话） */
    private String sessionId;
}

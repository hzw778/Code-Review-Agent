package com.codereview.agent.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求
 */
@Data
public class ChatRequest {

    /** 用户输入 */
    @NotBlank(message = "输入不能为空")
    private String message;
}

package com.codereview.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AgentProperties {
    /**
     * Agent 工作目录（克隆的 Git 仓库存放处）
     */
    private String workdir = "./workdir";

    /**
     * Agent 相关配置
     */
    private Agent agent = new Agent();

    @Data
    public static class Agent {
        /**
         * Agent 最大循环次数（防止死循环）
         */
        private int maxLoop = 10;

    }
}

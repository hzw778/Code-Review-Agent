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

    /**
     * Router 专用轻量模型配置
     */
    private RouterModel routerModel = new RouterModel();

    @Data
    public static class Agent {
        /**
         * Agent 最大循环次数（防止死循环）
         */
        private int maxLoop = 10;

    }

    @Data
    public static class RouterModel {
        /**
         * Router 模型的 API 地址
         */
        private String baseUrl = "https://ws-x8gbxzq7h6o17d3i.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

        /**
         * Router 模型的 API Key（与主模型共用）
         */
        private String apiKey = "your-api-key-here";

        /**
         * Router 模型名
         */
        private String model = "qwen-flash-2025-07-28";
    }
}

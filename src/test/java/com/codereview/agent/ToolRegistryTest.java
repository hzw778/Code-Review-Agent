package com.codereview.agent;

import com.codereview.agent.tool.AgentTool;
import com.codereview.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ToolRegistry 集成测试
 */
@SpringBootTest
class ToolRegistryTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void registry_shouldInit() {
        // 启动时应该完成初始化（0 个工具也正常，因为还没实现 AgentTool 适配）
        String descriptions = toolRegistry.getToolDescriptions();
        System.out.println("===== 工具描述 =====");
        System.out.println(descriptions);
        System.out.println("===== 工具列表 =====");
        System.out.println(toolRegistry.getToolNames());
    }

    @Test
    void getTool_nonExistent_shouldReturnNull() {
        AgentTool tool = toolRegistry.getTool("NonExistentTool");
        // 不存在应返回 null，不抛异常
        assert tool == null;
    }
}

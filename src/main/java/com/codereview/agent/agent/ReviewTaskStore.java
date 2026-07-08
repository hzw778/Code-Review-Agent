package com.codereview.agent.agent;

import com.codereview.agent.agent.model.AgentState;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 任务状态存储（内存版）
 *
 * <p>用 ConcurrentHashMap 存储任务状态，线程安全。
 * 学习阶段用内存存储，生产环境应换为数据库持久化。
 *
 * <p>设计说明：Agent 任务是长时序、有状态的，需要独立存储，
 * 不能依赖方法局部变量（异步执行后方法栈已弹出）。
 */
@Component
public class ReviewTaskStore {

    private final Map<String, AgentState> store = new ConcurrentHashMap<>();

    /**
     * 保存任务状态。
     */
    public void save(AgentState state) {
        store.put(state.getTaskId(), state);
    }

    /**
     * 查询任务状态。
     */
    public AgentState get(String taskId) {
        return store.get(taskId);
    }

    /**
     * 任务是否存在。
     */
    public boolean exists(String taskId) {
        return store.containsKey(taskId);
    }
}

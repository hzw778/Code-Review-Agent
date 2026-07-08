package com.codereview.agent.agent;

import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 代码审查编排服务
 *
 * <p>串联 Router → AgentLoop → Reflection，提供异步入口。
 *
 * <p>异步执行流程：
 * <ol>
 *   <li>submitReview 创建 AgentState（PENDING），存入 TaskStore</li>
 *   <li>调 ReviewAsyncRunner.runReviewAsync()（独立 bean，触发 @Async 代理）</li>
 *   <li>runReviewAsync 调 AgentLoop + Reflection，更新 AgentState</li>
 * </ol>
 *
 * <p>注意：@Async 方法必须通过注入其他 bean 调用，不能自调用（this.xxx() 不走 AOP 代理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewAsyncRunner reviewAsyncRunner;
    private final ReviewTaskStore taskStore;

    /**
     * 异步提交审查任务。
     *
     * <p>立即返回 taskId，后台执行 Agent 循环。
     *
     * @param repoUrl  仓库地址
     * @param commitId commit ID
     * @return taskId
     */
    public String submitReview(String repoUrl, String commitId) {
        // 创建初始状态
        AgentState state = new AgentState();
        state.setRepoUrl(repoUrl);
        state.setCommitId(commitId);
        state.setStatus(AgentStatus.PENDING);

        String taskId = UUID.randomUUID().toString();
        state.setTaskId(taskId);

        taskStore.save(state);
        log.info("[ReviewService] 审查任务已提交, taskId={}, repoUrl={}, commitId={}",
                taskId, repoUrl, commitId);

        // 通过独立 bean 调用，触发 @Async 代理
        reviewAsyncRunner.runReviewAsync(taskId, repoUrl, commitId);

        return taskId;
    }

    /**
     * 查询任务状态。
     */
    public AgentState getStatus(String taskId) {
        return taskStore.get(taskId);
    }

    /**
     * 查询任务结果（仅 SUCCESS 状态有结果）。
     */
    public String getResult(String taskId) {
        AgentState state = taskStore.get(taskId);
        if (state == null || state.getStatus() != AgentStatus.SUCCESS) {
            return null;
        }
        return state.getFinalResult();
    }
}

package com.codereview.agent.agent;

import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审查任务异步执行器
 *
 * <p>独立 bean，避免 @Async 自调用不触发代理的问题。
 * （ReviewService.submitReview 调 this.runReviewAsync() 不走 AOP 代理，@Async 失效）
 *
 * <p>执行流程：
 * <ol>
 *   <li>调 AgentLoop.run() 跑 ReAct 循环</li>
 *   <li>SUCCESS 才调 Reflection 自检</li>
 *   <li>更新 TaskStore</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewAsyncRunner {

    private final ReviewAgentLoop reviewAgentLoop;
    private final ReviewReflection reviewReflection;
    private final ReviewTaskStore taskStore;

    /**
     * 异步执行审查（@Async 注解让此方法在新线程执行）。
     *
     * <p>必须通过 Spring 代理调用才能触发 @Async，
     * 所以放在独立 bean 里被 ReviewService 注入调用。
     */
    @Async
    public void runReviewAsync(String taskId, String repoUrl, String commitId) {
        log.info("[ReviewAsyncRunner] 异步审查开始, taskId={}", taskId);
        try {
            // 1. Agent ReAct 循环（传入 taskId，内部每轮实时保存到 taskStore）
            AgentState state = reviewAgentLoop.run(taskId, repoUrl, commitId);

            // 2. 自检（仅 SUCCESS 才自检）
            if (state.getStatus() == AgentStatus.SUCCESS && state.getFinalResult() != null) {
                log.info("[ReviewAsyncRunner] 开始自检, taskId={}", taskId);
                String reflected = reviewReflection.reflect(state);
                state.setFinalResult(reflected);
                taskStore.save(state);  // 保存自检后的结果
            }

            log.info("[ReviewAsyncRunner] 审查完成, taskId={}, status={}", taskId, state.getStatus());
        } catch (Exception e) {
            log.error("[ReviewAsyncRunner] 审查异常, taskId={}", taskId, e);
            AgentState failed = new AgentState();
            failed.setTaskId(taskId);
            failed.setStatus(AgentStatus.FAILED);
            failed.setErrorMessage("审查异常: " + e.getMessage());
            taskStore.save(failed);
        }
    }
}

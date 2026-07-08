package com.codereview.agent.agent;

import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStatus;
import com.codereview.agent.repository.entity.ReviewRecord;
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
    private final ReviewReportService reviewReportService;
    private final TrajectoryService trajectoryService;

    /**
     * 异步执行审查（@Async 注解让此方法在新线程执行）。
     *
     * <p>必须通过 Spring 代理调用才能触发 @Async，
     * 所以放在独立 bean 里被 ReviewService 注入调用。
     *
     * <p>持久化时机：无论 SUCCESS 还是 FAILED，都在最后落库，
     * 保证审查记录重启不丢失（内存 TaskStore 重启会丢，DB 不会）。
     */
    @Async
    public void runReviewAsync(String taskId, String repoUrl, String commitId) {
        log.info("[ReviewAsyncRunner] 异步审查开始, taskId={}", taskId);
        AgentState state = null;
        try {
            // 1. Agent ReAct 循环（传入 taskId，内部每轮实时保存到 taskStore）
            state = reviewAgentLoop.run(taskId, repoUrl, commitId);

            // 2. 自检（仅 SUCCESS 才自检）
            if (state.getStatus() == AgentStatus.SUCCESS && state.getFinalResult() != null) {
                log.info("[ReviewAsyncRunner] 开始自检, taskId={}", taskId);
                String reflected = reviewReflection.reflect(state);
                state.setFinalResult(reflected);
                state.setUpdatedAt(System.currentTimeMillis());
                taskStore.save(state);  // 保存自检后的结果
            }

            log.info("[ReviewAsyncRunner] 审查完成, taskId={}, status={}", taskId, state.getStatus());
        } catch (Exception e) {
            log.error("[ReviewAsyncRunner] 审查异常, taskId={}", taskId, e);
            // 异常时构造失败状态（保留已执行的 steps 供排查）
            state = new AgentState();
            state.setTaskId(taskId);
            state.setRepoUrl(repoUrl);
            state.setCommitId(commitId);
            state.setStatus(AgentStatus.FAILED);
            state.setErrorMessage("审查异常: " + e.getMessage());
            state.setCreatedAt(System.currentTimeMillis());
            state.setUpdatedAt(System.currentTimeMillis());
            taskStore.save(state);
        } finally {
            // 3. 持久化到 DB（无论成功失败都落库，重启不丢失）
            //    报告 + 轨迹并行落库，互不依赖：
            //    - 报告是主要产物（issue 结构化），轨迹是辅助（ReAct 推理链复盘）
            //    - 任一失败不影响另一个，最大化保留数据
            if (state != null) {
                ReviewRecord record = null;
                try {
                    record = reviewReportService.persistReviewResult(state);
                } catch (Exception pe) {
                    // 报告落库失败不影响轨迹落库，仅记日志
                    log.error("[ReviewAsyncRunner] 报告持久化失败, taskId={}", taskId, pe);
                }
                try {
                    // 轨迹落库：传入 record 建立外键关联（record=null 也能落，靠 taskId 冗余字段）
                    trajectoryService.persistTrajectory(state, record);
                } catch (Exception te) {
                    // 轨迹落库失败不影响内存结果返回，仅记日志
                    log.error("[ReviewAsyncRunner] 轨迹持久化失败, taskId={}", taskId, te);
                }
            }
        }
    }
}

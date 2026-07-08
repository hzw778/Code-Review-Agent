package com.codereview.agent.agent;

import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStep;
import com.codereview.agent.repository.ReviewTrajectoryRepository;
import com.codereview.agent.repository.entity.ReviewRecord;
import com.codereview.agent.repository.entity.ReviewTrajectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行轨迹持久化服务
 *
 * <p>职责：
 * <ol>
 *   <li>持久化：Agent 审查完成后，把内存 AgentState.steps 落库为 ReviewTrajectory 列表</li>
 *   <li>查询：按 taskId 查询轨迹（供 steps 接口 DB 降级用）</li>
 * </ol>
 *
 * <p>与 ReviewReportService 的分工：
 * <ul>
 *   <li>ReviewReportService：管"报告 + issue"（问题维度，结构化）</li>
 *   <li>TrajectoryService：管"轨迹"（执行步骤维度，ReAct 推理链）</li>
 * </ul>
 * 两者在 finally 块并行落库，互不依赖。
 *
 * <p>设计要点：
 * <ul>
 *   <li>幂等：按 taskId 先删旧再插新，避免重复落库（如自检后重新落库）</li>
 *   <li>容错：单个 step 落库失败不影响其他 step（try-catch 在循环内），
 *       最大化保留轨迹完整性</li>
 *   <li>冗余 taskId：trajectory 表既有 record_id 外键也有 task_id 冗余字段，
 *       查轨迹不用 join record 表，直接按 taskId 查</li>
 * </ul>
 *
 * <p>Agent 设计经验——轨迹和报告为什么要分开存：
 * 报告是"结果"（LLM 输出的 Markdown + 解析出的 issue），轨迹是"过程"（每轮 Thought/Action/Observation）。
 * 报告小且高频查（历史列表），轨迹大且低频查（调试/复盘才看）。
 * 分开存储让"报告查询"不被"轨迹大字段"拖慢，也让"轨迹查询"能独立优化（如按 taskId 索引）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrajectoryService {

    private final ReviewTrajectoryRepository trajectoryRepository;

    /**
     * 持久化执行轨迹（Agent 审查完成后调用）。
     *
     * <p>幂等：若 taskId 已有轨迹则先删再插（如自检后重新落库）。
     *
     * @param state  Agent 执行完成后的状态（含 steps）
     * @param record 关联的审查记录（建立外键关系，可为 null 则不关联）
     * @return 落库的轨迹数量
     */
    @Transactional
    public int persistTrajectory(AgentState state, ReviewRecord record) {
        if (state == null || state.getTaskId() == null) {
            log.warn("[TrajectoryService] 持久化跳过：state 或 taskId 为空");
            return 0;
        }
        if (state.getSteps() == null || state.getSteps().isEmpty()) {
            log.info("[TrajectoryService] 持久化跳过：无 steps, taskId={}", state.getTaskId());
            return 0;
        }

        String taskId = state.getTaskId();
        log.info("[TrajectoryService] 开始持久化轨迹, taskId={}, stepCount={}",
                taskId, state.getSteps().size());

        // 幂等：先删旧轨迹
        trajectoryRepository.deleteByTaskId(taskId);
        trajectoryRepository.flush();

        // 逐条落库（容错：单条失败不影响其他）
        int success = 0;
        for (AgentStep step : state.getSteps()) {
            try {
                ReviewTrajectory traj = toEntity(step, taskId, record);
                trajectoryRepository.save(traj);
                success++;
            } catch (Exception e) {
                log.error("[TrajectoryService] 单条轨迹落库失败, taskId={}, round={}",
                        taskId, step.getRound(), e);
            }
        }

        log.info("[TrajectoryService] 持久化完成, taskId={}, 成功={}/{}",
                taskId, success, state.getSteps().size());
        return success;
    }

    /**
     * 按 taskId 查询轨迹（按轮次正序）。
     *
     * <p>供 steps 接口 DB 降级用：内存 TaskStore 丢失时从 DB 恢复轨迹。
     */
    @Transactional(readOnly = true)
    public List<ReviewTrajectory> getTrajectoryByTaskId(String taskId) {
        return trajectoryRepository.findByTaskIdOrderByRoundAsc(taskId);
    }

    /** AgentStep → ReviewTrajectory 实体转换 */
    private ReviewTrajectory toEntity(AgentStep step, String taskId, ReviewRecord record) {
        ReviewTrajectory traj = new ReviewTrajectory();
        traj.setReviewRecord(record);
        traj.setTaskId(taskId);
        traj.setRound(step.getRound());
        traj.setThought(step.getThought());
        traj.setAction(step.getAction());
        traj.setActionInput(step.getActionInput());
        traj.setObservation(step.getObservation());
        traj.setCostMs(step.getCostMs());
        traj.setPromptSentToLlm(step.getPromptSentToLlm());
        traj.setLlmRawResponse(step.getLlmRawResponse());
        traj.setCreatedAt(LocalDateTime.now());
        return traj;
    }
}

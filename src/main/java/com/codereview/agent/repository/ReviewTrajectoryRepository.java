package com.codereview.agent.repository;

import com.codereview.agent.repository.entity.ReviewTrajectory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Agent 执行轨迹 Repository
 *
 * <p>提供按 taskId 查询轨迹（前端轮询用）和按 taskId 删除（幂等更新用）。
 */
public interface ReviewTrajectoryRepository extends JpaRepository<ReviewTrajectory, Long> {

    /**
     * 按 taskId 查询轨迹，按轮次正序（ReAct 推理链顺序）。
     *
     * <p>轨迹必须按轮次顺序展示，否则 Thought/Action/Observation 链断裂无法理解。
     */
    List<ReviewTrajectory> findByTaskIdOrderByRoundAsc(String taskId);

    /**
     * 按 taskId 删除轨迹（幂等更新时先删旧再插新）。
     */
    @Transactional
    void deleteByTaskId(String taskId);
}

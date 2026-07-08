package com.codereview.agent.repository.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Agent 执行轨迹实体类
 *
 * <p>对应数据库表 review_trajectory，存储 ReAct 循环的每一步（Thought/Action/Observation）。
 * 与内存 TaskStore 中的 AgentStep 对应：审查完成后落库，重启不丢失轨迹。
 *
 * <p>设计要点：
 * <ul>
 *   <li>与 ReviewRecord 一对多：一个审查任务有 N 个轨迹步骤</li>
 *   <li>独立表而非塞进 ReviewRecord：steps 含 promptSentToLlm/llmRawResponse 大字段
 *       （每个 step 几 KB），塞进记录表会让历史列表查询慢。独立表让"记录查询（轻）"
 *       和"轨迹查询（重）"分离</li>
 *   <li>与 ReviewIssue 平行：ReviewIssue 是"问题维度"子表，
 *       ReviewTrajectory 是"执行步骤维度"子表，两者都关联 ReviewRecord</li>
 *   <li>按 round ASC 排序：round 是整数，无 ReviewIssue 的枚举字典序排序坑</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "review_trajectory",
        indexes = {
                @Index(name = "idx_traj_record_id", columnList = "record_id"),
                @Index(name = "idx_traj_task_id", columnList = "task_id")
        })
public class ReviewTrajectory {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的审查记录 ID（外键） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "record_id")
    @ToString.Exclude
    private ReviewRecord reviewRecord;

    /** 任务唯一 ID（冗余字段，便于不 join 直接按 taskId 查轨迹） */
    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    /** 轮次（从 1 开始，对应 ReAct 循环的第几轮） */
    @Column(name = "round", nullable = false)
    private Integer round;

    /** LLM 的思考内容（Thought） */
    @Column(columnDefinition = "TEXT")
    private String thought;

    /** 调用的工具名（如 GitDiffTool），Finish 表示完成 */
    @Column(length = 64)
    private String action;

    /** 工具的输入参数（JSON 字符串） */
    @Column(name = "action_input", columnDefinition = "TEXT")
    private String actionInput;

    /** 工具的返回结果（JSON 字符串，Observation） */
    @Column(columnDefinition = "TEXT")
    private String observation;

    /** 本轮耗时（毫秒） */
    @Column(name = "cost_ms")
    private Long costMs;

    /** 发给 LLM 的完整 prompt（含系统指令+历史步骤+当前任务） */
    @Column(name = "prompt_sent_to_llm", columnDefinition = "LONGTEXT")
    private String promptSentToLlm;

    /** LLM 返回的原始内容（未解析的 JSON 字符串） */
    @Column(name = "llm_raw_response", columnDefinition = "TEXT")
    private String llmRawResponse;

    /** 创建时间（落库时间） */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

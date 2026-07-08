package com.codereview.agent.repository.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码审查记录实体类
 * <p>
 * 对应数据库表 review_record，存储一次完整 Agent 审查任务的持久化结果。
 * 与内存 TaskStore 中的 AgentState 对应：审查完成后落库，重启不丢失。
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>taskId 唯一索引：通过 taskId 关联内存轨迹与持久化报告</li>
 *   <li>冗余统计字段（criticalCount 等）：历史列表页无需 join issue 表即可展示统计</li>
 *   <li>finalResult 用 TEXT：Agent 输出的 Markdown 报告可能较长</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "review_record",
        indexes = {@Index(name = "idx_task_id", columnList = "task_id", unique = true)})
public class ReviewRecord {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务唯一 ID（与 AgentState.taskId 一致，供前端轮询与报告查询） */
    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    private String taskId;

    /** 仓库地址 */
    @Column(name = "repo_url", length = 500)
    private String repoUrl;

    /** 仓库名称（冗余字段，便于历史列表展示，无需 join git_repo） */
    @Column(name = "repo_name", length = 100)
    private String repoName;

    /** commit ID */
    @Column(name = "commit_id", length = 64)
    private String commitId;

    /** 审查最终状态：SUCCESS / FAILED */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ReviewStatus status;

    /** Agent 输出的最终 Markdown 报告（Finish 的 result） */
    @Column(columnDefinition = "TEXT")
    private String finalResult;

    /** 问题总数（解析 finalResult 得到，冗余字段） */
    @Column(name = "issue_count")
    private Integer issueCount;

    /** CRITICAL 严重问题数 */
    @Column(name = "critical_count")
    private Integer criticalCount;

    /** MAJOR 主要问题数 */
    @Column(name = "major_count")
    private Integer majorCount;

    /** MINOR 次要问题数 */
    @Column(name = "minor_count")
    private Integer minorCount;

    /** INFO 提示数 */
    @Column(name = "info_count")
    private Integer infoCount;

    /** Agent 执行总轮次 */
    @Column(name = "total_steps")
    private Integer totalSteps;

    /** Agent 执行总耗时（毫秒） */
    @Column(name = "total_cost_ms")
    private Long totalCostMs;

    /** 失败原因（FAILED 时有值） */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 创建时间（任务提交时间） */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 完成时间（审查结束时间） */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 关联的问题列表（一对多）。
     * <p>mappedBy 表示 ReviewIssue 维护外键关系；
     * cascade + orphanRemoval 保证删除记录时级联删除问题。
     * <p>@ToString.Exclude 防止与 ReviewIssue.reviewRecord 形成 toString 递归（StackOverflow）。
     */
    @OneToMany(mappedBy = "reviewRecord", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("severityOrder ASC, id ASC")
    @ToString.Exclude
    private List<ReviewIssue> issues = new ArrayList<>();

    /**
     * 审查状态枚举
     */
    public enum ReviewStatus {
        SUCCESS, FAILED
    }
}

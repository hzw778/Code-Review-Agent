package com.codereview.agent.repository.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 代码审查问题实体类
 * <p>
 * 对应数据库表 review_issue，存储从 Agent 报告中解析出的单条问题。
 * 一条 ReviewRecord 对应多条 ReviewIssue（一对多）。
 * </p>
 *
 * <p>解析来源：Agent Finish 输出的 Markdown 报告，按行正则匹配
 * 形如 "[CRITICAL] UserService.java:42 空 catch 块" 的问题行。</p>
 *
 * <p>severityOrder 字段说明：
 * 用整数存储严重度排序（CRITICAL=0 &lt; MAJOR=1 &lt; MINOR=2 &lt; INFO=3），
 * 便于 ReviewRecord.issues 按 @OrderBy 排序，避免在 SQL 里对枚举排序的兼容性问题。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "review_issue",
        indexes = {@Index(name = "idx_record_id", columnList = "record_id")})
public class ReviewIssue {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属审查记录（外键）。@ToString.Exclude 防止与 ReviewRecord.issues 形成 toString 递归。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "record_id", nullable = false)
    @ToString.Exclude
    private ReviewRecord reviewRecord;

    /** 严重度：CRITICAL / MAJOR / MINOR / INFO */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Severity severity;

    /** 严重度排序值（CRITICAL=0, MAJOR=1, MINOR=2, INFO=3），用于排序 */
    @Column(name = "severity_order")
    private Integer severityOrder;

    /** 规则类型（如 EMPTY_CATCH、RESOURCE_LEAK，可为空） */
    @Column(name = "rule_type", length = 50)
    private String ruleType;

    /** 涉及文件（如 UserService.java，可为空） */
    @Column(name = "file_path", length = 500)
    private String filePath;

    /** 涉及行号（解析失败为 null） */
    @Column(name = "line_number")
    private Integer lineNumber;

    /** 问题描述 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    /** 修改建议（可为空） */
    @Column(columnDefinition = "TEXT")
    private String suggestion;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 严重度枚举（含排序值）。
     * <p>ordinal() 顺序即严重度从高到低，与 severityOrder 一致。</p>
     */
    public enum Severity {
        CRITICAL(0), MAJOR(1), MINOR(2), INFO(3);

        private final int order;

        Severity(int order) {
            this.order = order;
        }

        public int getOrder() {
            return order;
        }

        /** 不区分大小写解析，解析失败返回 INFO */
        public static Severity fromString(String s) {
            if (s == null) return INFO;
            try {
                return Severity.valueOf(s.trim().toUpperCase());
            } catch (Exception e) {
                return INFO;
            }
        }
    }
}

package com.codereview.agent.agent;

import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStatus;
import com.codereview.agent.agent.model.AgentStep;
import com.codereview.agent.repository.ReviewIssueRepository;
import com.codereview.agent.repository.ReviewRecordRepository;
import com.codereview.agent.repository.entity.ReviewIssue;
import com.codereview.agent.repository.entity.ReviewRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审查报告持久化与查询服务
 *
 * <p>职责：
 * <ol>
 *   <li>持久化：Agent 审查完成后，把内存 AgentState 落库为 ReviewRecord + ReviewIssue</li>
 *   <li>解析：从 Agent 输出的 Markdown 报告中正则解析出结构化 issue</li>
 *   <li>查询：提供历史列表、报告详情（含 issue 分组）</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>幂等：按 taskId 查询，已存在则更新，避免重复落库</li>
 *   <li>解析容错：Markdown 格式不严格，正则匹配失败时 issue 列表为空，不影响记录持久化</li>
 *   <li>事务：落库 + 解析在同一个 @Transactional 内，保证一致性</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewReportService {

    private final ReviewRecordRepository recordRepository;
    private final ReviewIssueRepository issueRepository;

    /**
     * 匹配问题行：[SEVERITY] 后跟可选 文件:行号 和描述。
     * <p>容忍多种写法：
     * <ul>
     *   <li>[CRITICAL] UserService.java:50 资源泄漏</li>
     *   <li>1. [MAJOR] Foo.java:42 - 空 catch 块</li>
     *   <li>[MINOR] 命名不规范</li>
     * </ul>
     */
    private static final Pattern ISSUE_LINE_PATTERN =
            Pattern.compile("\\[(CRITICAL|MAJOR|MINOR|INFO)\\]\\s*(.*)");

    /** 从问题行剩余文本中提取 文件:行号 */
    private static final Pattern FILE_LINE_PATTERN =
            Pattern.compile("([\\w./$]+\\.[A-Za-z]+):(\\d+)");

    /** 分割描述与建议的常见分隔符 */
    private static final Pattern SUGGESTION_SPLIT =
            Pattern.compile("[，,。;；]\\s*(?:建议|suggestion|建议：|suggestion:)\\s*",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 持久化审查结果（Agent 审查完成后调用）。
     *
     * <p>幂等：若 taskId 已存在则更新（如自检后重新落库），否则新增。
     *
     * @param state Agent 执行完成后的状态
     * @return 持久化后的 ReviewRecord
     */
    @Transactional
    public ReviewRecord persistReviewResult(AgentState state) {
        if (state == null || state.getTaskId() == null) {
            log.warn("[ReviewReportService] 持久化跳过：state 或 taskId 为空");
            return null;
        }

        String taskId = state.getTaskId();
        log.info("[ReviewReportService] 开始持久化审查结果, taskId={}, status={}",
                taskId, state.getStatus());

        // 幂等：已存在则更新（先删旧 issue 再解析新的）
        ReviewRecord record = recordRepository.findByTaskId(taskId).orElse(null);
        boolean isUpdate = record != null;
        if (record == null) {
            record = new ReviewRecord();
            record.setTaskId(taskId);
            record.setCreatedAt(LocalDateTime.now());
        }

        // 填充记录字段
        record.setRepoUrl(state.getRepoUrl());
        record.setRepoName(extractRepoName(state.getRepoUrl()));
        record.setCommitId(state.getCommitId());
        record.setStatus(state.getStatus() == AgentStatus.SUCCESS
                ? ReviewRecord.ReviewStatus.SUCCESS
                : ReviewRecord.ReviewStatus.FAILED);
        record.setFinalResult(state.getFinalResult());
        record.setErrorMessage(state.getErrorMessage());
        record.setTotalSteps(state.getSteps() != null ? state.getSteps().size() : 0);
        record.setTotalCostMs(computeTotalCostMs(state));
        record.setCompletedAt(LocalDateTime.now());

        // 解析 issue（仅 SUCCESS 且有结果时才解析）
        List<ReviewIssue> issues = new ArrayList<>();
        if (state.getStatus() == AgentStatus.SUCCESS && state.getFinalResult() != null) {
            issues = parseIssues(state.getFinalResult());
        }

        // 更新统计字段
        record.setIssueCount(issues.size());
        record.setCriticalCount(countBySeverity(issues, ReviewIssue.Severity.CRITICAL));
        record.setMajorCount(countBySeverity(issues, ReviewIssue.Severity.MAJOR));
        record.setMinorCount(countBySeverity(issues, ReviewIssue.Severity.MINOR));
        record.setInfoCount(countBySeverity(issues, ReviewIssue.Severity.INFO));

        // 更新场景：先清旧 issue（orphanRemoval 会在 save 时处理，但显式删更稳妥）
        if (isUpdate) {
            issueRepository.deleteByReviewRecordId(record.getId());
            issueRepository.flush();
        }

        // 保存记录
        record = recordRepository.save(record);

        // 保存 issue（建立双向关联）
        for (ReviewIssue issue : issues) {
            issue.setReviewRecord(record);
            issue.setCreatedAt(LocalDateTime.now());
        }
        issueRepository.saveAll(issues);

        log.info("[ReviewReportService] 持久化完成, taskId={}, recordId={}, issueCount={}, isUpdate={}",
                taskId, record.getId(), issues.size(), isUpdate);
        return record;
    }

    /**
     * 查询审查历史（按时间倒序，仅摘要字段）。
     */
    @Transactional(readOnly = true)
    public List<ReviewRecord> getHistory() {
        return recordRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 根据 taskId 查询完整报告（含 issue 列表）。
     *
     * <p>使用 JOIN FETCH 一次性加载 issues，避免 Controller 层
     * 触发懒加载导致 LazyInitializationException。
     */
    @Transactional(readOnly = true)
    public ReviewRecord getReportByTaskId(String taskId) {
        return recordRepository.findWithIssuesByTaskId(taskId).orElse(null);
    }

    /**
     * 从 Markdown 报告中解析结构化 issue。
     *
     * <p>逐行匹配 [SEVERITY] 标记，提取文件/行号/描述/建议。
     * 格式不规范时返回空列表（不影响记录持久化）。
     */
    List<ReviewIssue> parseIssues(String markdown) {
        List<ReviewIssue> issues = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return issues;
        }

        String[] lines = markdown.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            // 去掉行首序号 "1. " / "- " / "* "
            line = line.replaceFirst("^\\d+\\.\\s*", "");
            line = line.replaceFirst("^[-*]\\s*", "");

            Matcher m = ISSUE_LINE_PATTERN.matcher(line);
            if (!m.find()) {
                continue;
            }

            String severityStr = m.group(1);
            String rest = m.group(2).trim();

            ReviewIssue.Severity severity = ReviewIssue.Severity.fromString(severityStr);
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(severity);
            issue.setSeverityOrder(severity.getOrder());

            // 尝试提取 文件:行号
            Matcher flm = FILE_LINE_PATTERN.matcher(rest);
            if (flm.find()) {
                issue.setFilePath(flm.group(1));
                issue.setLineNumber(Integer.parseInt(flm.group(2)));
                rest = rest.substring(flm.end()).trim();
            }

            // 去掉行首的分隔符 "- " "："
            rest = rest.replaceFirst("^[-—–:：\\s]+", "");

            // 分割描述与建议
            String[] parts = SUGGESTION_SPLIT.split(rest, 2);
            issue.setMessage(parts[0].trim());
            if (parts.length > 1 && !parts[1].isBlank()) {
                issue.setSuggestion(parts[1].trim());
            }

            // 尝试从描述中提取规则类型（全大写下划线词，如 EMPTY_CATCH）
            Matcher rtm = Pattern.compile("\\b([A-Z][A-Z_]{3,})\\b").matcher(parts[0]);
            if (rtm.find()) {
                issue.setRuleType(rtm.group(1));
            }

            if (!issue.getMessage().isBlank()) {
                issues.add(issue);
            }
        }

        log.debug("[ReviewReportService] 解析完成, 共 {} 条 issue", issues.size());
        return issues;
    }

    /** 从仓库 URL 提取仓库名（用于历史列表展示） */
    private String extractRepoName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return null;
        }
        String name = repoUrl;
        // 去掉 .git 后缀
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        // 取最后一段
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    /** 计算总耗时：updatedAt - createdAt（毫秒） */
    private Long computeTotalCostMs(AgentState state) {
        if (state.getCreatedAt() == 0 || state.getUpdatedAt() == 0) {
            // 退化：累加各步骤耗时（costMs 是基本类型 long，无需判空）
            long sum = 0;
            if (state.getSteps() != null) {
                for (AgentStep s : state.getSteps()) {
                    sum += s.getCostMs();
                }
            }
            return sum;
        }
        return state.getUpdatedAt() - state.getCreatedAt();
    }

    private int countBySeverity(List<ReviewIssue> issues, ReviewIssue.Severity sev) {
        return (int) issues.stream().filter(i -> i.getSeverity() == sev).count();
    }
}

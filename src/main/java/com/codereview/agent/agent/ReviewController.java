package com.codereview.agent.agent;

import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStatus;
import com.codereview.agent.agent.model.AgentStep;
import com.codereview.agent.guardrail.GuardrailResult;
import com.codereview.agent.guardrail.PromptInjectionDetector;
import com.codereview.agent.repository.entity.ReviewIssue;
import com.codereview.agent.repository.entity.ReviewRecord;
import com.codereview.agent.repository.entity.ReviewTrajectory;

import com.codereview.agent.model.ApiResponse;
import com.codereview.agent.service.RepoService;
import com.codereview.agent.repository.entity.GitRepo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码审查 HTTP 接口
 *
 * <p>接口清单：
 * <ul>
 *   <li>POST /review/start：发起审查（异步，立即返回 taskId）</li>
 *   <li>POST /review/by-repo：通过已注册仓库 ID 发起审查</li>
 *   <li>GET /review/{taskId}/status：查询状态（内存优先，DB 降级）</li>
 *   <li>GET /review/{taskId}/result：获取结果（内存优先，DB 降级）</li>
 *   <li>GET /review/{taskId}/steps：获取完整 ReAct 执行轨迹（内存优先，DB 降级）</li>
 *   <li>GET /review/history：审查历史列表（DB 持久化）</li>
 *   <li>GET /review/{taskId}/report：完整报告 + 结构化 issue 分组（DB 持久化）</li>
 * </ul>
 *
 * <p>内存 vs DB（两层存储互为降级）：
 * <ul>
 *   <li>审查进行中：内存 TaskStore 有实时数据（step 正在生成），DB 还没落库（finally 才落），
 *       必须查内存</li>
 *   <li>审查完成或重启后：内存可能丢失（ConcurrentHashMap 重启清空），
 *       steps 从 DB TrajectoryService 恢复，report/history 直接查 DB</li>
 *   <li>报告（ReviewRecord/ReviewIssue）和轨迹（ReviewTrajectory）独立落库，互不依赖</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final RepoService repoService;
    private final ReviewReportService reviewReportService;
    private final TrajectoryService trajectoryService;
    private final PromptInjectionDetector promptInjectionDetector;

    /**
     * 发起代码审查（异步，直接传 repoUrl）。
     *
     * <p>立即返回 taskId，后台执行 Agent 循环。
     * 用 taskId 轮询 /status 和 /result。
     */
    @PostMapping("/start")
    public ApiResponse<Map<String, String>> startReview(@Valid @RequestBody ReviewRequest request) {
        log.info("[ReviewController] 收到审查请求, repoUrl={}, commitId={}",
                request.getRepoUrl(), request.getCommitId());

        // 输入护栏：repoUrl/commitId 最终会被拼进 Agent system prompt 送 LLM，做注入检测
        // 命中即拒绝启动 Agent，省 git clone + 整个 ReAct 循环的 LLM 调用成本
        GuardrailResult repoGuard = promptInjectionDetector.detect(request.getRepoUrl());
        if (repoGuard.blocked()) {
            log.warn("[ReviewController] repoUrl 命中注入护栏: reason={}", repoGuard.reason());
            return ApiResponse.error(400, "repoUrl 被安全护栏拦截: " + repoGuard.reason());
        }
        GuardrailResult commitGuard = promptInjectionDetector.detect(request.getCommitId());
        if (commitGuard.blocked()) {
            log.warn("[ReviewController] commitId 命中注入护栏: reason={}", commitGuard.reason());
            return ApiResponse.error(400, "commitId 被安全护栏拦截: " + commitGuard.reason());
        }

        String taskId = reviewService.submitReview(request.getRepoUrl(), request.getCommitId());

        return ApiResponse.success(Map.of("taskId", taskId));
    }

    /**
     * 通过已注册仓库 ID 发起审查（异步）。
     *
     * <p>前端选仓库 + 选 commit 后调用此接口。
     * 内部把 repoId → GitRepo.url，再走原 submitReview 流程。
     */
    @PostMapping("/by-repo")
    public ApiResponse<Map<String, String>> startReviewByRepo(@RequestBody Map<String, Object> body) {
        Object repoIdObj = body.get("repoId");
        Object commitIdObj = body.get("commitId");
        if(repoIdObj == null || commitIdObj == null){
            return ApiResponse.error(400, "repoId 和 commitId 不能为空");
        }
        Long repoId = Long.valueOf(String.valueOf(repoIdObj));
        String commitId = String.valueOf(commitIdObj);

        // 输入护栏：commitId 会进 Agent prompt，做注入检测（repoId 是 Long 不需要）
        GuardrailResult commitGuard = promptInjectionDetector.detect(commitId);
        if (commitGuard.blocked()) {
            log.warn("[ReviewController] commitId 命中注入护栏: reason={}", commitGuard.reason());
            return ApiResponse.error(400, "commitId 被安全护栏拦截: " + commitGuard.reason());
        }

        GitRepo repo = repoService.getRepoById(repoId);
        if(repo == null){
            return ApiResponse.error(404, "仓库不存在: " + repoId);
        }
        log.info("[ReviewController] 通过仓库ID发起审查, repoId={}, repoUrl={}, commitId={}",
                repoId, repo.getUrl(), commitId);
        String taskId = reviewService.submitReview(repo.getUrl(), commitId);
        return ApiResponse.success(Map.of("taskId", taskId, "repoName", repo.getName()));
    }

    /**
     * 查询审查任务状态。
     *
     * <p>返回当前轮次、已完成步骤数、最新一步的动作（便于前端展示进度）。
     */
    @GetMapping("/{taskId}/status")
    public ApiResponse<Map<String, Object>> getStatus(@PathVariable String taskId) {
        AgentState state = reviewService.getStatus(taskId);
        if (state == null) {
            return ApiResponse.error(404, "任务不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", state.getTaskId());
        data.put("status", state.getStatus().name());
        data.put("currentRound", state.getCurrentRound());
        data.put("totalSteps", state.getSteps() != null ? state.getSteps().size() : 0);
        data.put("repoUrl", state.getRepoUrl());
        data.put("commitId", state.getCommitId());
        data.put("errorMessage", state.getErrorMessage() != null ? state.getErrorMessage() : "");
        // 附带最新一步摘要，便于前端实时展示"正在做什么"
        if (state.getSteps() != null && !state.getSteps().isEmpty()) {
            AgentStep latest = state.getSteps().get(state.getSteps().size() - 1);
            Map<String, Object> latestStep = new LinkedHashMap<>();
            latestStep.put("round", latest.getRound());
            latestStep.put("action", latest.getAction());
            latestStep.put("thought", latest.getThought());
            latestStep.put("costMs", latest.getCostMs());
            data.put("latestStep", latestStep);
        }
        return ApiResponse.success(data);
    }

    /**
     * 获取审查结果（仅 SUCCESS 状态有结果）。
     */
    @GetMapping("/{taskId}/result")
    public ApiResponse<Map<String, Object>> getResult(@PathVariable String taskId) {
        AgentState state = reviewService.getStatus(taskId);
        if (state == null) {
            return ApiResponse.error(404, "任务不存在");
        }
        if (state.getStatus() != AgentStatus.SUCCESS) {
            return ApiResponse.error(400, "任务未完成，当前状态: " + state.getStatus());
        }
        return ApiResponse.success(Map.of(
                "taskId", state.getTaskId(),
                "status", state.getStatus().name(),
                "result", state.getFinalResult(),
                "totalSteps", state.getSteps() != null ? state.getSteps().size() : 0,
                "errorMessage", state.getErrorMessage() != null ? state.getErrorMessage() : ""
        ));
    }

    /**
     * 获取完整 ReAct 执行轨迹（thought / action / actionInput / observation / costMs）。
     *
     * <p>供前端"数据传输过程"侧边面板可视化使用：
     * <ul>
     *   <li>每一轮 LLM 的思考内容（thought）</li>
     *   <li>调用的工具名和参数（action / actionInput）</li>
     *   <li>工具返回的数据（observation）</li>
     *   <li>耗时（costMs）</li>
     * </ul>
     *
     * <p>数据源策略（内存优先 + DB 降级）：
     * <ol>
     *   <li>审查进行中：内存 TaskStore 有实时数据（step 正在生成），DB 还没落库（finally 才落），
     *       必须查内存</li>
     *   <li>审查完成或重启后：内存可能丢失（ConcurrentHashMap 重启清空），从 DB TrajectoryService 恢复</li>
     * </ol>
     * 两层存储互为降级，保证轨迹在任何情况下都能查到。
     */
    @GetMapping("/{taskId}/steps")
    public ApiResponse<Map<String, Object>> getSteps(@PathVariable String taskId) {
        // 1. 内存优先（实时性）
        AgentState state = reviewService.getStatus(taskId);
        if (state != null && state.getSteps() != null && !state.getSteps().isEmpty()) {
            List<Map<String, Object>> stepList = new ArrayList<>();
            for (AgentStep step : state.getSteps()) {
                stepList.add(stepToMap(step));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", state.getTaskId());
            data.put("status", state.getStatus().name());
            data.put("currentRound", state.getCurrentRound());
            data.put("totalSteps", stepList.size());
            data.put("source", "memory");
            data.put("steps", stepList);
            return ApiResponse.success(data);
        }

        // 2. DB 降级（内存丢失时从持久化恢复）
        List<ReviewTrajectory> trajectory = trajectoryService.getTrajectoryByTaskId(taskId);
        if (trajectory.isEmpty()) {
            return ApiResponse.error(404, "任务不存在或轨迹未持久化");
        }
        List<Map<String, Object>> stepList = new ArrayList<>();
        for (ReviewTrajectory traj : trajectory) {
            stepList.add(trajectoryToMap(traj));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("status", "PERSISTED");  // DB 恢复的轨迹，状态已固化
        data.put("currentRound", stepList.size());
        data.put("totalSteps", stepList.size());
        data.put("source", "db");
        data.put("steps", stepList);
        return ApiResponse.success(data);
    }

    /** AgentStep（内存）→ Map */
    private Map<String, Object> stepToMap(AgentStep step) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("round", step.getRound());
        s.put("thought", step.getThought());
        s.put("action", step.getAction());
        s.put("actionInput", step.getActionInput());
        s.put("observation", step.getObservation());
        s.put("costMs", step.getCostMs());
        s.put("promptSentToLlm", step.getPromptSentToLlm());
        s.put("llmRawResponse", step.getLlmRawResponse());
        return s;
    }

    /** ReviewTrajectory（DB）→ Map */
    private Map<String, Object> trajectoryToMap(ReviewTrajectory traj) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("round", traj.getRound());
        s.put("thought", traj.getThought());
        s.put("action", traj.getAction());
        s.put("actionInput", traj.getActionInput());
        s.put("observation", traj.getObservation());
        s.put("costMs", traj.getCostMs());
        s.put("promptSentToLlm", traj.getPromptSentToLlm());
        s.put("llmRawResponse", traj.getLlmRawResponse());
        return s;
    }

    /**
     * 查询审查历史（DB 持久化，按时间倒序）。
     *
     * <p>返回摘要列表（不含 finalResult 和 issues，保持响应体精简）。
     * 前端用于渲染历史记录列表，点击某条再调 /report 拿详情。
     */
    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> getHistory() {
        List<ReviewRecord> records = reviewReportService.getHistory();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReviewRecord r : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("taskId", r.getTaskId());
            item.put("repoName", r.getRepoName());
            item.put("repoUrl", r.getRepoUrl());
            item.put("commitId", r.getCommitId());
            item.put("status", r.getStatus().name());
            item.put("issueCount", r.getIssueCount());
            item.put("criticalCount", r.getCriticalCount());
            item.put("majorCount", r.getMajorCount());
            item.put("minorCount", r.getMinorCount());
            item.put("infoCount", r.getInfoCount());
            item.put("totalSteps", r.getTotalSteps());
            item.put("totalCostMs", r.getTotalCostMs());
            item.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            item.put("completedAt", r.getCompletedAt() != null ? r.getCompletedAt().toString() : null);
            list.add(item);
        }
        return ApiResponse.success(list);
    }

    /**
     * 查询完整审查报告（DB 持久化）。
     *
     * <p>返回内容：
     * <ul>
     *   <li>记录摘要（状态、统计、耗时）</li>
     *   <li>原始 Markdown 报告（finalResult）</li>
     *   <li>结构化 issue 列表，按严重度分组（CRITICAL/MAJOR/MINOR/INFO）</li>
     * </ul>
     *
     * <p>前端用 issues 分组渲染"按严重度分组的问题列表"，
     * 用 finalResult 作为兜底展示（当 issue 解析为空时仍能看到原始报告）。
     */
    @GetMapping("/{taskId}/report")
    public ApiResponse<Map<String, Object>> getReport(@PathVariable String taskId) {
        ReviewRecord record = reviewReportService.getReportByTaskId(taskId);
        if (record == null) {
            return ApiResponse.error(404, "报告不存在: " + taskId);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", record.getTaskId());
        data.put("repoName", record.getRepoName());
        data.put("repoUrl", record.getRepoUrl());
        data.put("commitId", record.getCommitId());
        data.put("status", record.getStatus().name());
        data.put("finalResult", record.getFinalResult());
        data.put("errorMessage", record.getErrorMessage());
        data.put("issueCount", record.getIssueCount());
        data.put("criticalCount", record.getCriticalCount());
        data.put("majorCount", record.getMajorCount());
        data.put("minorCount", record.getMinorCount());
        data.put("infoCount", record.getInfoCount());
        data.put("totalSteps", record.getTotalSteps());
        data.put("totalCostMs", record.getTotalCostMs());
        data.put("createdAt", record.getCreatedAt() != null ? record.getCreatedAt().toString() : null);
        data.put("completedAt", record.getCompletedAt() != null ? record.getCompletedAt().toString() : null);

        // issue 按严重度分组
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        grouped.put("CRITICAL", new ArrayList<>());
        grouped.put("MAJOR", new ArrayList<>());
        grouped.put("MINOR", new ArrayList<>());
        grouped.put("INFO", new ArrayList<>());
        if (record.getIssues() != null) {
            for (ReviewIssue issue : record.getIssues()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("id", issue.getId());
                im.put("severity", issue.getSeverity().name());
                im.put("ruleType", issue.getRuleType());
                im.put("filePath", issue.getFilePath());
                im.put("lineNumber", issue.getLineNumber());
                im.put("message", issue.getMessage());
                im.put("suggestion", issue.getSuggestion());
                grouped.get(issue.getSeverity().name()).add(im);
            }
        }
        data.put("issues", grouped);

        return ApiResponse.success(data);
    }
}

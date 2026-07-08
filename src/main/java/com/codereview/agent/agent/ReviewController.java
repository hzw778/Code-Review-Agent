package com.codereview.agent.agent;

import com.codereview.agent.agent.model.AgentState;
import com.codereview.agent.agent.model.AgentStatus;
import com.codereview.agent.agent.model.AgentStep;

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
 * <p>四个接口：
 * <ul>
 *   <li>POST /review/start：发起审查（异步，立即返回 taskId）</li>
 *   <li>GET /review/{id}/status：查询状态</li>
 *   <li>GET /review/{id}/result：获取结果</li>
 *   <li>GET /review/{id}/steps：获取完整 ReAct 执行轨迹（供前端可视化）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final RepoService repoService;

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
     */
    @GetMapping("/{taskId}/steps")
    public ApiResponse<Map<String, Object>> getSteps(@PathVariable String taskId) {
        AgentState state = reviewService.getStatus(taskId);
        if (state == null) {
            return ApiResponse.error(404, "任务不存在");
        }
        List<Map<String, Object>> stepList = new ArrayList<>();
        if (state.getSteps() != null) {
            for (AgentStep step : state.getSteps()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("round", step.getRound());
                s.put("thought", step.getThought());
                s.put("action", step.getAction());
                s.put("actionInput", step.getActionInput());
                s.put("observation", step.getObservation());
                s.put("costMs", step.getCostMs());
                s.put("promptSentToLlm", step.getPromptSentToLlm());
                s.put("llmRawResponse", step.getLlmRawResponse());
                stepList.add(s);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", state.getTaskId());
        data.put("status", state.getStatus().name());
        data.put("currentRound", state.getCurrentRound());
        data.put("totalSteps", stepList.size());
        data.put("steps", stepList);
        return ApiResponse.success(data);
    }
}

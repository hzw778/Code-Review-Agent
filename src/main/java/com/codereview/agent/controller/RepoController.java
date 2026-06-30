package com.codereview.agent.controller;

import com.codereview.agent.git.GitOperationService;
import com.codereview.agent.git.model.DiffEntry;
import com.codereview.agent.model.ApiResponse;
import com.codereview.agent.model.RepoRequest;
import com.codereview.agent.repository.entity.GitRepo;
import com.codereview.agent.service.RepoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/repo")
@RequiredArgsConstructor
public class RepoController {

    private final RepoService repoService;

    /**
     * 注册新仓库
     * @return ApiResponse 包含注册成功的 GitRepo 信息
     * POST http://localhost:8080/repo
     */
    @PostMapping
    public ApiResponse<GitRepo> register(@Valid @RequestBody RepoRequest request){
        log.info("[接口入口] POST /repo - 注册仓库 name={}", request.getName());
        GitRepo repo = repoService.registerRepo(request);
        return ApiResponse.success("仓库注册成功",repo);
    }

    /**
     * 查询所有仓库
     * @return ApiResponse 包含所有 GitRepo 信息的列表
     * GET http://localhost:8080/repo
     */
    @GetMapping
    public ApiResponse<List<GitRepo>> listRepos(){
        log.info("[接口入口] GET /repo - 查询所有仓库");
        return ApiResponse.success("查询所有仓库成功", repoService.listRepos());
    }

    /**
     * 根据 ID 查询仓库
     * GET http://localhost:8080/repo/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<GitRepo> getById(@PathVariable Long id) {
        log.info("[接口入口] GET /repo/{}", id);
        GitRepo repo = repoService.getRepoById(id);
        return ApiResponse.success(repo);
    }

    /**
     * 克隆仓库到本地
     * POST http://localhost:8080/repo/{id}/clone
     */
    @PostMapping("/{id}/clone")
    public ApiResponse<Void> clone(@PathVariable Long id){
        log.info("[接口入口] POST /repo/{}/clone - 克隆仓库", id);
        repoService.cloneRepo(id);
        return ApiResponse.success("仓库克隆成功", null);
    }

    /**
     * 查询仓库的 commit 历史
     * GET http://localhost:8080/repo/{id}/commits?limit=20
     */
    @GetMapping("/{id}/commits")
    public ApiResponse<List<GitOperationService.CommitInfo>> listCommits(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit) {
        log.info("[接口入口] GET /repo/{}/commits?limit={}", id, limit);
        List<GitOperationService.CommitInfo> commits = repoService.listCommits(id, limit);
        return ApiResponse.success(commits);
    }

    /**
     * 获取指定 commit 的 diff
     * GET http://localhost:8080/repo/{id}/diff/{commitId}
     */
    @GetMapping("/{id}/diff/{commitId}")
    public ApiResponse<DiffEntry> getDiff(
            @PathVariable Long id,
            @PathVariable String commitId) {
        log.info("[接口入口] GET /repo/{}/diff/{}", id, commitId);
        DiffEntry diff = repoService.getDiff(id, commitId);
        return ApiResponse.success(diff);
    }

    /**
     * 删除仓库
     * DELETE http://localhost:8080/repo/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("[接口入口] DELETE /repo/{}", id);
        repoService.deleteRepo(id);
        return ApiResponse.success("仓库删除成功", null);
    }
}

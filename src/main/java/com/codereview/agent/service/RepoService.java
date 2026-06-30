package com.codereview.agent.service;


import com.codereview.agent.git.GitOperationService;
import com.codereview.agent.git.model.DiffEntry;
import com.codereview.agent.model.RepoRequest;
import com.codereview.agent.repository.entity.GitRepo;

import java.util.List;

/**
 * 仓库业务服务接口
 * 定义仓库管理的业务操作：注册、查询、克隆、commit 历史、diff。
 */
public interface RepoService {

    /**
     * 注册新仓库
     */
    GitRepo registerRepo(RepoRequest request);

    /**
     * 查询所有仓库
     */
    List<GitRepo> listRepos();

    /**
     * 根据 ID 查询仓库
     */
    GitRepo getRepoById(Long id);

    /**
     * 克隆仓库到本地
     */
    void cloneRepo(Long repoId);

    /**
     * 查询仓库的 commit 历史
     */
    List<GitOperationService.CommitInfo> listCommits(Long repoId, int limit);

    /**
     * 获取指定 commit 的 diff
     */
    DiffEntry getDiff(Long repoId, String commitId);

    /**
     * 删除仓库（同时删除本地文件）
     */
    void deleteRepo(Long repoId);
}

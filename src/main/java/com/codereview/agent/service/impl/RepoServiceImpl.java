package com.codereview.agent.service.impl;

import com.codereview.agent.config.AgentProperties;
import com.codereview.agent.exception.BusinessException;
import com.codereview.agent.exception.ErrorCode;
import com.codereview.agent.git.GitOperationService;
import com.codereview.agent.git.model.DiffEntry;
import com.codereview.agent.model.RepoRequest;
import com.codereview.agent.repository.GitRepoRepository;
import com.codereview.agent.repository.entity.GitRepo;
import com.codereview.agent.service.RepoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 仓库业务服务实现
 *
 * @author CodeReviewAgent
 * @date 2026-06-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoServiceImpl implements RepoService {

    private final GitRepoRepository gitRepoRepository;
    private final GitOperationService gitOperationService;
    private final AgentProperties agentProperties;

    @Override
    @Transactional
    public GitRepo registerRepo(RepoRequest request) {
        log.info("[数据流转] 注册仓库开始 - name={}, url={}", request.getName(), request.getUrl());

        // 校验仓库名是否重复
        if (gitRepoRepository.existsByName(request.getName())) {
            log.warn("[异常] 仓库名已存在 - name={}", request.getName());
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "仓库名已存在: " + request.getName());
        }

        // 构建实体
        GitRepo repo = new GitRepo();
        repo.setName(request.getName());
        repo.setUrl(request.getUrl());
        repo.setType(request.getType());
        repo.setDefaultBranch(request.getDefaultBranch() != null ? request.getDefaultBranch() : "main");
        repo.setLocalPath(agentProperties.getWorkdir() + File.separator + request.getName());
        repo.setStatus(GitRepo.RepoStatus.UNCLONED);
        repo.setCreatedAt(LocalDateTime.now());
        repo.setUpdatedAt(LocalDateTime.now());

        // 保存到数据库
        GitRepo saved = gitRepoRepository.save(repo);
        log.info("[数据流转] 仓库注册成功 - id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    @Override
    public List<GitRepo> listRepos() {
        log.info("[数据流转] 查询所有仓库");
        return gitRepoRepository.findAll();
    }

    @Override
    public GitRepo getRepoById(Long id) {
        log.info("[数据流转] 根据ID查询仓库 - id={}", id);
        return gitRepoRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPO_NOT_FOUND,
                        "仓库不存在: id=" + id));
    }

    @Override
    @Transactional
    public void cloneRepo(Long repoId) {
        GitRepo repo = getRepoById(repoId);
        log.info("[数据流转] 开始克隆仓库 - repoId={}, name={}", repoId, repo.getName());

        try {
            gitOperationService.cloneRepo(repo);
            // 克隆成功，更新状态
            repo.setStatus(GitRepo.RepoStatus.CLONED);
            repo.setUpdatedAt(LocalDateTime.now());
            gitRepoRepository.save(repo);
            log.info("[数据流转] 仓库克隆状态已更新为 CLONED");
        } catch (Exception e) {
            // 克隆失败，更新状态
            repo.setStatus(GitRepo.RepoStatus.FAILED);
            repo.setUpdatedAt(LocalDateTime.now());
            gitRepoRepository.save(repo);
            throw e; // 重新抛出，由全局异常处理器处理
        }
    }

    @Override
    public List<GitOperationService.CommitInfo> listCommits(Long repoId, int limit) {
        GitRepo repo = getRepoById(repoId);
        if (repo.getStatus() != GitRepo.RepoStatus.CLONED) {
            throw new BusinessException(ErrorCode.GIT_OPERATION_FAILED,
                    "仓库未克隆，请先克隆: " + repo.getName());
        }
        return gitOperationService.listCommits(repo, limit);
    }

    @Override
    public DiffEntry getDiff(Long repoId, String commitId) {
        GitRepo repo = getRepoById(repoId);
        if (repo.getStatus() != GitRepo.RepoStatus.CLONED) {
            throw new BusinessException(ErrorCode.GIT_OPERATION_FAILED,
                    "仓库未克隆，请先克隆: " + repo.getName());
        }
        return gitOperationService.getDiff(repo, commitId);
    }

    @Override
    @Transactional
    public void deleteRepo(Long repoId) {
        GitRepo repo = getRepoById(repoId);
        log.info("[数据流转] 删除仓库 - repoId={}, name={}", repoId, repo.getName());

        // 删除本地克隆目录
        File localDir = new File(repo.getLocalPath());
        if (localDir.exists()) {
            deleteDirectory(localDir);
            log.info("[数据流转] 本地目录已删除 - path={}", localDir.getAbsolutePath());
        }

        // 删除数据库记录
        gitRepoRepository.delete(repo);
        log.info("[数据流转] 仓库记录已删除");
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
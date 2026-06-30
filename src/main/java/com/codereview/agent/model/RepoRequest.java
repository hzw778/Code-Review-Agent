package com.codereview.agent.model;

import com.codereview.agent.repository.entity.GitRepo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 仓库注册请求 DTO
 * 接收前端注册仓库的请求参数。
 * 使用 @Valid 注解触发校验。
 */
@Data
public class RepoRequest {

    /** 仓库名称（必填，唯一） */
    @NotBlank(message = "仓库名称不能为空")
    private String name;

    /** 仓库地址（必填）：本地路径或远程 URL */
    @NotBlank(message = "仓库地址不能为空")
    private String url;

    /** 仓库类型：LOCAL / REMOTE */
    @NotNull(message = "仓库类型不能为空")
    private GitRepo.RepoType type;

    /** 默认分支（可选，默认 main） */
    private String defaultBranch;
}
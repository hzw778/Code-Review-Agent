package com.codereview.agent.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 代码审查请求
 */
@Data
public class ReviewRequest {

    /** Git 仓库地址 */
    @NotBlank(message = "仓库地址不能为空")
    private String repoUrl;

    /** Commit ID */
    @NotBlank(message = "commit ID 不能为空")
    private String commitId;
}

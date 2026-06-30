package com.codereview.agent.repository.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Git 仓库实体类
 * <p>
 * 对应数据库表 git_repo，存储用户注册的 Git 仓库信息。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "git_repo")
public class GitRepo {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 仓库名称（用户自定义，便于识别） */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** 仓库地址（本地路径或远程 URL） */
    @Column(nullable = false, length = 500)
    private String url;

    /** 仓库类型：LOCAL（本地）/ REMOTE（远程） */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private RepoType type;

    /** 默认分支（如 main / master） */
    @Column(length = 50)
    private String defaultBranch;

    /** 本地克隆路径（系统克隆后存储的位置） */
    @Column(length = 500)
    private String localPath;

    /** 仓库状态：UNCLONED（未克隆）/ CLONED（已克隆）/ FAILED（克隆失败） */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private RepoStatus status;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 仓库类型枚举
     */
    public enum RepoType {
        LOCAL, REMOTE
    }

    /**
     * 仓库状态枚举
     */
    public enum RepoStatus {
        UNCLONED, CLONED, FAILED
    }
}
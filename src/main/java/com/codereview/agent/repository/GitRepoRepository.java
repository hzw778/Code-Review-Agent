package com.codereview.agent.repository;

import com.codereview.agent.repository.entity.GitRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GitRepoRepository extends JpaRepository<GitRepo, Long> {

    /**
     * 根据仓库名查询
     */
    Optional<GitRepo> findByName(String name);

    /**
     * 判断仓库名是否已存在（用于注册时校验重名）
     */
    boolean existsByName(String name);
}
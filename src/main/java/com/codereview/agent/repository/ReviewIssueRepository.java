package com.codereview.agent.repository;

import com.codereview.agent.repository.entity.ReviewIssue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 审查问题 Repository
 */
public interface ReviewIssueRepository extends JpaRepository<ReviewIssue, Long> {

    /** 查询某条审查记录下的所有问题（按严重度排序） */
    List<ReviewIssue> findByReviewRecordIdOrderBySeverityOrderAscIdAsc(Long recordId);

    /** 删除某条审查记录下的所有问题（重新解析前清理） */
    void deleteByReviewRecordId(Long recordId);
}

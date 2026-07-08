package com.codereview.agent.repository;

import com.codereview.agent.repository.entity.ReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 审查记录 Repository
 */
public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, Long> {

    /** 根据 taskId 查询（taskId 唯一） */
    Optional<ReviewRecord> findByTaskId(String taskId);

    /** 根据 taskId 查询并一次性加载 issues（避免 LazyInitializationException） */
    @Query("SELECT r FROM ReviewRecord r LEFT JOIN FETCH r.issues WHERE r.taskId = :taskId")
    Optional<ReviewRecord> findWithIssuesByTaskId(@Param("taskId") String taskId);

    /** 历史列表：按创建时间倒序 */
    List<ReviewRecord> findAllByOrderByCreatedAtDesc();

    /** 判断 taskId 是否已存在（幂等检查） */
    boolean existsByTaskId(String taskId);
}

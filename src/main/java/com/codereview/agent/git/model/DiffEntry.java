package com.codereview.agent.git.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整 Diff 模型
 * 表示一次 commit（或两次 commit 之间）的完整变更，包含多个文件的 Diff。
 *
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffEntry {

    /** 触发 diff 的 commit ID（或两个 commit 之间） */
    private String commitId;

    /** 作者 */
    private String author;

    /** 提交信息 */
    private String commitMessage;

    /** 提交时间（毫秒时间戳） */
    private long commitTime;

    /** 变更的文件列表 */
    private List<DiffFile> files = new ArrayList<>();

    /**
     * 获取所有新增行总数
     */
    public int getAddedLineCount() {
        return files.stream()
                .mapToInt(f -> f.getAddedLines().size())
                .sum();
    }

    /**
     * 获取所有删除行总数
     */
    public int getRemovedLineCount() {
        return files.stream()
                .mapToInt(f -> f.getRemovedLines().size())
                .sum();
    }
}
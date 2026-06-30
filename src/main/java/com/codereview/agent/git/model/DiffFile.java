package com.codereview.agent.git.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个文件的 Diff 模型
 * 表示一个文件的所有变更内容，包括文件路径、新增行、删除行。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffFile {

    /** 旧文件路径（删除时为 /dev/null） */
    private String oldPath;

    /** 新文件路径（新增文件时为 /dev/null） */
    private String newPath;

    /** 文件变更类型：ADD（新增）/ MODIFY（修改）/ DELETE（删除）/ RENAME（重命名） */
    private String changeType;

    /** 新增的行（含行号和内容） */
    private List<DiffLine> addedLines = new ArrayList<>();

    /** 删除的行（含行号和内容） */
    private List<DiffLine> removedLines = new ArrayList<>();

    /**
     * Diff 行模型（内部类）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffLine {
        /** 行号 */
        private int lineNumber;
        /** 行内容 */
        private String content;
    }

    /**
     * 获取新文件路径（用于后续 AST 分析）
     */
    public String getDisplayPath() {
        // 优先返回新路径，删除文件时返回旧路径
        if (newPath != null && !newPath.equals("/dev/null")) {
            return newPath;
        }
        return oldPath;
    }
}
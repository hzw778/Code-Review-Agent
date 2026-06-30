package com.codereview.agent.git;

import com.codereview.agent.exception.BusinessException;
import com.codereview.agent.exception.ErrorCode;
import com.codereview.agent.git.model.DiffEntry;
import com.codereview.agent.git.model.DiffFile;
import com.codereview.agent.repository.entity.GitRepo;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git 操作服务
 * 基于 JGit 封装 Git 仓库操作：克隆、读 commit 历史、读 diff。
 */
@Slf4j
@Service
public class GitOperationService {

    /**
     * 克隆远程仓库到本地
     *
     * @param repo 仓库信息
     * @return 本地仓库目录
     */
    public File cloneRepo(GitRepo repo) {
        File targetDir = new File(repo.getLocalPath());
        log.info("[Git操作] 开始克隆仓库, name={}, url={}, target={}",
                repo.getName(), repo.getUrl(), targetDir.getAbsolutePath());

        long startTime = System.currentTimeMillis();
        try {
            // 如果目录已存在，先删除
            if (targetDir.exists()) {
                log.info("[Git操作] 目标目录已存在, 先删除: {}", targetDir.getAbsolutePath());
                deleteDirectory(targetDir);
            }

            // 执行克隆
            try (Git git = Git.cloneRepository()
                    .setURI(repo.getUrl())
                    .setDirectory(targetDir)
                    .setBranch(repo.getDefaultBranch())
                    .call()) {
                log.info("[Git操作] 仓库克隆成功, branch={}", repo.getDefaultBranch());
            }

            long costMs = System.currentTimeMillis() - startTime;
            log.info("[Git操作] 克隆完成, 耗时={}ms", costMs);
            return targetDir;

        } catch (GitAPIException e) {
            long costMs = System.currentTimeMillis() - startTime;
            log.error("[异常] 仓库克隆失败, 耗时={}ms, 错误={}", costMs, e.getMessage(), e);
            throw new BusinessException(ErrorCode.REPO_CLONE_FAILED,
                    "仓库克隆失败: " + e.getMessage());
        }
    }

    /**
     * 列出仓库的最近提交历史
     *
     * @param repo 仓库信息
     * @param limit 最多返回多少条
     * @return Commit 列表
     */
    public List<CommitInfo> listCommits(GitRepo repo, int limit) {
        log.info("[Git操作] 查询commit历史, repoName={}, limit={}", repo.getName(), limit);

        File repoDir = new File(repo.getLocalPath());
        if (!repoDir.exists()) {
            throw new BusinessException(ErrorCode.REPO_NOT_FOUND,
                    "仓库本地目录不存在，请先克隆: " + repo.getLocalPath());
        }

        List<CommitInfo> commits = new ArrayList<>();
        try (Git git = Git.open(repoDir)) {
            // git.log() 返回 RevCommit 的迭代器
            Iterable<RevCommit> logIterable = git.log().setMaxCount(limit).call();
            for (RevCommit commit : logIterable) {
                commits.add(new CommitInfo(
                        commit.getId().getName(),               // commit SHA-1
                        commit.getAuthorIdent().getName(),      // 作者
                        commit.getAuthorIdent().getEmailAddress(), // 邮箱
                        commit.getCommitTime() * 1000L,         // 提交时间（JGit 返回秒，转毫秒）
                        commit.getShortMessage(),               // 提交标题
                        commit.getFullMessage()                 // 完整提交信息
                ));
            }
            log.info("[Git操作] 查询commit历史成功, 返回{}条", commits.size());
            return commits;

        } catch (IOException | GitAPIException e) {
            log.error("[异常] 查询commit历史失败, 错误={}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.GIT_OPERATION_FAILED,
                    "查询 commit 历史失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定 commit 相对于其父 commit 的 diff
     *
     * @param repo     仓库信息
     * @param commitId commit SHA-1
     * @return DiffEntry（自定义模型，非 JGit 的 DiffEntry）
     */
    public DiffEntry getDiff(GitRepo repo, String commitId) {
        log.info("[Git操作] 查询diff, repoName={}, commitId={}", repo.getName(), commitId);

        File repoDir = new File(repo.getLocalPath());
        if (!repoDir.exists()) {
            throw new BusinessException(ErrorCode.REPO_NOT_FOUND,
                    "仓库本地目录不存在: " + repo.getLocalPath());
        }

        long startTime = System.currentTimeMillis();
        try (Git git = Git.open(repoDir);
             RevWalk revWalk = new RevWalk(git.getRepository())) {

            Repository repository = git.getRepository();

            // 解析目标 commit
            ObjectId commitObj = repository.resolve(commitId);
            RevCommit commit = revWalk.parseCommit(commitObj);

            // 构建新旧树迭代器
            // 新树：目标 commit 的目录树
            AbstractTreeIterator newTree = getTreeParser(repository, commit);
            // 旧树：父 commit 的目录树（如果是首个 commit，用空树）
            AbstractTreeIterator oldTree;
            if (commit.getParentCount() > 0) {
                RevCommit parentCommit = revWalk.parseCommit(commit.getParent(0).getId());
                oldTree = getTreeParser(repository, parentCommit);
            } else {
                oldTree = new EmptyTreeIterator();
            }

            // 执行 diff
            // 注意：不能用 DisabledOutputStream（write 会抛 IllegalStateException），
            //       要用 NullOutputStream（静默丢弃写入）。
            //       DiffCommand.call() 内部会 flush，所以必须给一个能接受 write 的流。
            //       这里不需要 DiffCommand 输出的原始 diff 文本，行级解析在下面 parseDiffWithFormatter 单独做。
            List<org.eclipse.jgit.diff.DiffEntry> diffs = git.diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .setOutputStream(NullOutputStream.INSTANCE)
                    .call();

            // 用 DiffFormatter 解析每个文件的行级变更
            DiffEntry result = parseDiffWithFormatter(repository, diffs, commit);

            long costMs = System.currentTimeMillis() - startTime;
            log.info("[Git操作] diff查询成功, 耗时={}ms, 文件数={}, 新增行={}, 删除行={}",
                    costMs, result.getFiles().size(), result.getAddedLineCount(), result.getRemovedLineCount());
            return result;

        } catch (IOException | GitAPIException e) {
            long costMs = System.currentTimeMillis() - startTime;
            log.error("[异常] diff查询失败, 耗时={}ms, 错误={}", costMs, e.getMessage(), e);
            throw new BusinessException(ErrorCode.GIT_OPERATION_FAILED,
                    "查询 diff 失败: " + e.getMessage());
        }
    }

    /**
     * 读取仓库中指定文件的内容
     */
    public String readFileContent(GitRepo repo, String branch, String filePath) {
        log.info("[Git操作] 读取文件, repoName={}, branch={}, file={}", repo.getName(), branch, filePath);

        File repoDir = new File(repo.getLocalPath());
        try (Git git = Git.open(repoDir)) {
            // 直接从工作目录读取（已 clone 且 checkout 到 branch）
            File targetFile = new File(repoDir, filePath);
            if (!targetFile.exists()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "文件不存在: " + filePath);
            }
            return java.nio.file.Files.readString(targetFile.toPath());

        } catch (IOException e) {
            log.error("[异常] 读取文件失败, 错误={}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.GIT_OPERATION_FAILED,
                    "读取文件失败: " + e.getMessage());
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建 commit 的目录树迭代器（用于 diff）
     */
    private AbstractTreeIterator getTreeParser(Repository repository, RevCommit commit) throws IOException {
        try (ObjectReader reader = repository.newObjectReader()) {
            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            treeParser.reset(reader, commit.getTree());
            return treeParser;
        }
    }

    /**
     * 用 DiffFormatter 输出 diff 文本，再手动解析 + / - 行
     * <p>
     * 这种方式不依赖 JGit 内部 API（forEachHunk 等方法版本差异大），
     * 直接解析标准 diff 文本格式，稳定可靠。
     * </p>
     */
    private DiffEntry parseDiffWithFormatter(Repository repository,
                                             List<org.eclipse.jgit.diff.DiffEntry> diffs,
                                             RevCommit commit) throws IOException {
        DiffEntry result = new DiffEntry();
        result.setCommitId(commit.getId().getName());
        result.setAuthor(commit.getAuthorIdent().getName());
        result.setCommitMessage(commit.getFullMessage());
        result.setCommitTime(commit.getCommitTime() * 1000L);

        // 用 ByteArrayOutputStream 接收 diff 文本输出
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(repository);

            for (org.eclipse.jgit.diff.DiffEntry diff : diffs) {
                // 1. 把当前文件的 diff 输出到 out
                out.reset();
                formatter.format(diff);
                String diffText = out.toString(StandardCharsets.UTF_8);

                // 2. 解析 diff 文本
                DiffFile diffFile = parseDiffText(diffText, diff);
                result.getFiles().add(diffFile);
            }
        }

        return result;
    }

    /**
     * 解析单个文件的 diff 文本，提取新增行和删除行
     * 标准 diff 格式：
     * diff --git a/x b/x
     * index abc..def 100644
     * --- a/x          （旧文件路径）
     * +++ b/x          （新文件路径）
     * @@ -10,5 +10,8 @@  （行号信息：旧文件从第10行起5行，新文件从第10行起8行）
     *  context line    （空格开头：上下文行）
     * -removed line    （减号开头：删除行）
     * +added line      （加号开头：新增行）
     */
    private DiffFile parseDiffText(String diffText, org.eclipse.jgit.diff.DiffEntry diff) {
        DiffFile diffFile = new DiffFile();
        diffFile.setOldPath(diff.getOldPath());
        diffFile.setNewPath(diff.getNewPath());
        diffFile.setChangeType(diff.getChangeType().name());

        List<DiffFile.DiffLine> addedLines = new ArrayList<>();
        List<DiffFile.DiffLine> removedLines = new ArrayList<>();

        // 匹配 @@ -oldStart,oldCount +newStart,newCount @@ 提取起始行号
        // 行号是 1-based，但 hunk 后续行号要累加
        Pattern hunkPattern = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
        int oldLineNum = 0;  // 旧文件当前行号
        int newLineNum = 0;  // 新文件当前行号

        String[] lines = diffText.split("\n");
        for (String line : lines) {
            // 匹配 hunk 头，重置行号计数器
            Matcher m = hunkPattern.matcher(line);
            if (m.find()) {
                oldLineNum = Integer.parseInt(m.group(1));
                newLineNum = Integer.parseInt(m.group(2));
                continue;
            }

            // 跳过 diff 头部信息行（diff/index/---/+++）
            if (line.startsWith("diff --git") || line.startsWith("index ")
                    || line.startsWith("--- ") || line.startsWith("+++ ")
                    || line.startsWith("new file") || line.startsWith("deleted file")
                    || line.startsWith("old mode") || line.startsWith("new mode")
                    || line.startsWith("similarity ") || line.startsWith("rename ")
                    || line.startsWith("copy ")) {
                continue;
            }

            // 新增行（+开头，但要排除 +++ 文件头）
            if (line.startsWith("+") && !line.startsWith("+++")) {
                String content = line.substring(1);  // 去掉 + 号
                addedLines.add(new DiffFile.DiffLine(newLineNum, content));
                newLineNum++;
            }
            // 删除行（-开头，但要排除 --- 文件头）
            else if (line.startsWith("-") && !line.startsWith("---")) {
                String content = line.substring(1);  // 去掉 - 号
                removedLines.add(new DiffFile.DiffLine(oldLineNum, content));
                oldLineNum++;
            }
            // 上下文行（空格开头或空行）
            else if (line.startsWith(" ") || line.isEmpty()) {
                oldLineNum++;
                newLineNum++;
            }
            // 反斜杠开头（如 \ No newline at end of file）忽略
        }

        diffFile.setAddedLines(addedLines);
        diffFile.setRemovedLines(removedLines);
        return diffFile;
    }

    /**
     * 递归删除目录（用于重新克隆前清理）
     */
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

    /**
     * Commit 简要信息（内部类）
     */
    public record CommitInfo(
            String commitId,
            String author,
            String email,
            long commitTime,
            String shortMessage,
            String fullMessage
    ) {}
}
package com.codereview.agent.controller;

import com.codereview.agent.model.ApiResponse;
import com.codereview.agent.model.KnowledgeIngestRequest;
import com.codereview.agent.model.KnowledgeIngestResult;
import com.codereview.agent.rag.CodeVectorService;
import com.codereview.agent.rag.KnowledgeIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理接口
 *
 * <p>提供知识入库的 HTTP 入口，用户更新规范文档或代码示例后调用。
 */
@Slf4j
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeIngestService knowledgeIngestService;
    private final CodeVectorService codeVectorService;

    /**
     * 触发知识入库（全量）
     *
     * <p>POST http://localhost:8080/knowledge/ingest
     *
     * @param request 入库请求（type 可选：rule / code / all）
     * @return 入库结果统计
     */
    @PostMapping("/ingest")
    public ApiResponse<KnowledgeIngestResult> ingest(@RequestBody(required = false) KnowledgeIngestRequest request){
        log.info("[接口入口] POST /knowledge/ingest - 触发知识入库, request={}", request);
        String type = (request == null || request.getType() == null) ? "all" : request.getType();
        int ruleCount = 0;
        int codeCount = 0;
        if ("all".equalsIgnoreCase(type) || "rule".equalsIgnoreCase(type)) {
            ruleCount = knowledgeIngestService.ingestAll();
        }
        if ("all".equalsIgnoreCase(type) || "code".equalsIgnoreCase(type)) {
            codeCount = codeVectorService.ingestAll();
        }
        KnowledgeIngestResult result = KnowledgeIngestResult.builder()
                .ruleCount(ruleCount)
                .codeCount(codeCount)
                .totalCount(ruleCount + codeCount)
                .build();

        log.info("[接口出口] 知识入库完成, rule={}, code={}, total={}",
                ruleCount, codeCount, result.getTotalCount());
        return ApiResponse.success("知识入库成功", result);
    }
}

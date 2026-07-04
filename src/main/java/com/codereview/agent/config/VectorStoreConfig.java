package com.codereview.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 向量库配置（常量定义）
 * <p>
 * 核心职责：集中管理 RAG 相关常量（向量维度、索引名）。
 * </p>
 *
 * <p>设计说明：</p>
 * <ul>
 *   <li>VectorStore Bean 由 Spring AI 自动配置创建（application.yml 中
 *       spring.ai.vectorstore.elasticsearch.initialize-schema=true）</li>
 *   <li>本类只提供常量，后续 RuleChunker / CodeChunker / 检索 Service 会引用</li>
 *   <li>不手动创建 VectorStore Bean，避免和 Spring AI 自动配置冲突</li>
 * </ul>
 *
 * <p>Embedding 模型：GLM text-embedding-v3，输出 1024 维向量</p>
 * <p>向量库：Elasticsearch，索引名 code-review-vector</p>
 * <p>相似度算法：余弦相似度（Cosine Similarity），文本检索标准选择</p>
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    /** GLM text-embedding-v3 模型输出维度（必须和 embedding 模型维度对齐） */
    public static final int EMBEDDING_DIMENSION = 1024;

    /** 向量库索引名（和 application.yml 中 spring.ai.vectorstore.elasticsearch.index-name 一致） */
    public static final String INDEX_NAME = "code-review-vector";

    /** 文档类型：规范文档（区别于代码 chunk） */
    public static final String DOC_TYPE_RULE = "rule";

    /** 文档类型：代码片段 */
    public static final String DOC_TYPE_CODE = "code";
}

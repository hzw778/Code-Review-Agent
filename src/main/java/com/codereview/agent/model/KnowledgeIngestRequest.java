package com.codereview.agent.model;

import lombok.Data;

/**
 * 知识入库请求
 *
 * <p>当前不传参数（全量入库），预留 type 字段支持后续按类型入库。
 */
@Data
public class KnowledgeIngestRequest {

    /**
     * 入库类型：rule / code / all
     * null 或 all 表示全部入库
     */
    private String type;
}

package com.codereview.agent;

import com.codereview.agent.agent.ReviewRouter;
import com.codereview.agent.agent.model.RouterResult;
import com.codereview.agent.agent.model.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ReviewRouter 集成测试
 * 需要 LLM API 可用
 */
@SpringBootTest
class ReviewRouterTest {

    @Autowired
    private ReviewRouter reviewRouter;

    @Test
    void route_chitchat_shouldReturnChitchat() {
        RouterResult result = reviewRouter.route("你好，你是做什么的？");
        assertEquals(TaskType.CHITCHAT, result.getTaskType());
        System.out.println("闲聊分类: " + result.getTaskType() + ", 耗时=" + result.getCostMs() + "ms");
    }

    @Test
    void route_codeQa_shouldReturnCodeQa() {
        RouterResult result = reviewRouter.route("什么是 try-with-resources？为什么要用它？");
        assertEquals(TaskType.CODE_QA, result.getTaskType());
        System.out.println("问答分类: " + result.getTaskType() + ", 耗时=" + result.getCostMs() + "ms");
    }

    @Test
    void route_review_shouldReturnReview() {
        RouterResult result = reviewRouter.route("帮我审查这个 commit，看看代码有什么问题");
        assertEquals(TaskType.REVIEW, result.getTaskType());
        System.out.println("审查分类: " + result.getTaskType() + ", 耗时=" + result.getCostMs() + "ms");
    }
}

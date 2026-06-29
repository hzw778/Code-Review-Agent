package com.codereview.agent.exception;

import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 用于表示业务逻辑错误（如仓库不存在、参数非法等）。
 * 会被 GlobalExceptionHandler 捕获并转为统一响应格式。
 * </p>
 *
 * @author CodeReviewAgent
 * @date 2026-06-29
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 错误码 */
    private final int code;

    /**
     * 通过 ErrorCode 构造
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 通过 ErrorCode + 自定义消息构造
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /**
     * 通过 code + message 构造
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
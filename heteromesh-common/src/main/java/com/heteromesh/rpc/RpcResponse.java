package com.heteromesh.rpc;

import lombok.Getter;
import lombok.Setter;

/**
 * RPC 调用响应，由 Worker 端构造，序列化后放在 TASK_RESPONSE 的 body 里。
 *
 * 使用约定：
 * 1. Client 端拿到 RpcResponse 后，先检查 isSuccess()
 * 2. isSuccess() == true  → 取 getResult()
 * 3. isSuccess() == false → 取 getStatus() + getErrorMessage()
 */
@Getter
@Setter
public class RpcResponse {

    /** 响应状态 */
    private RpcStatus status;

    /** 方法返回值（仅 SUCCESS 时有意义） */
    private Object result;

    /** 错误描述（仅非 SUCCESS 时有意义） */
    private String errorMessage;

    /** 无参构造（Gson 反序列化需要） */
    public RpcResponse() {
    }

    // ===== 静态工厂方法 =====

    /** 构造一个成功响应 */
    public static RpcResponse success(Object result) {
        RpcResponse resp = new RpcResponse();
        resp.status = RpcStatus.SUCCESS;
        resp.result = result;
        return resp;
    }

    /** 构造一个失败响应 */
    public static RpcResponse error(RpcStatus status, String errorMessage) {
        RpcResponse resp = new RpcResponse();
        resp.status = status;
        resp.errorMessage = errorMessage;
        return resp;
    }

    /** 构造一个超时响应 */
    public static RpcResponse timeout(String message) {
        return error(RpcStatus.TIMEOUT, message);
    }

    // ===== 便捷判断方法 =====

    public boolean isSuccess() {
        return status == RpcStatus.SUCCESS;
    }
}

package com.heteromesh.rpc;

/**
 * RPC 调用响应状态。
 * Client 端拿到 RpcResponse 后先检查 status，再决定如何处理 result/errorMessage。
 */
public enum RpcStatus {

    /** 调用成功 */
    SUCCESS(0),

    /** 调用超时（Client 端在指定时间内未收到响应） */
    TIMEOUT(1),

    /** 服务未找到（Worker 端没有注册对应的接口实现） */
    NOT_FOUND(2),

    /** 方法未找到（服务已注册，但没有匹配的方法） */
    METHOD_NOT_FOUND(3),

    /** 业务方法执行时抛出异常 */
    ERROR(4),

    /** 框架内部错误（序列化/反序列化失败、网络异常等） */
    FRAMEWORK_ERROR(5);

    private final int code;

    RpcStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

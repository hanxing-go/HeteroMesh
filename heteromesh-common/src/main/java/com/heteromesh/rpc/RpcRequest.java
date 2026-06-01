package com.heteromesh.rpc;

import lombok.Data;

/**
 * 一次 RPC 请求的描述，序列化后放在 TASK_REQUEST 的 body 里。
 *
 * 只包含 RPC 层面的信息：调哪个方法 + 等多久 + 要不要回复。
 * requestId 是传输层的事，由 RpcClient 管理，不放在这里。
 */
@Data
public class RpcRequest {

    /** 具体的调用信息（服务名 + 方法名 + 参数） */
    private RpcInvocation invocation;

    /** 超时毫秒数（0 表示不限制） */
    private long timeoutMs;

    /** 单向调用：true = 发了不管结果 */
    private boolean oneWay;
}

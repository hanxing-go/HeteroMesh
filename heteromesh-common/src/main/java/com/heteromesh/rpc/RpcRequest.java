package com.heteromesh.rpc;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * 一次 RPC 请求的完整描述，序列化后放在 TASK_REQUEST 的 body 里。
 *
 * RpcRequest = RPC 传输元信息 + RpcInvocation（方法调用内容）
 *
 * 分离的好处：
 * - RpcInvocation 只管「调哪个类的哪个方法」，可在多次重试中复用
 * - RpcRequest 管「这次请求的追踪 ID」「等多久」「要不要回复」
 * - 重试时：同一个 invocation，不同的 requestId
 */
@Data
public class RpcRequest {

    /** 请求追踪 ID，每次调用唯一 */
    private String requestId;

    /** 具体的调用信息（服务名 + 方法名 + 参数） */
    private RpcInvocation invocation;

    /** 超时毫秒数（0 表示不限制） */
    private long timeoutMs;

    /** 单向调用：true = 发了不管结果 */
    private boolean oneWay;

    /** 无参构造（Gson 反序列化需要），自动生成 requestId */
    public RpcRequest() {
        this.requestId = UUID.randomUUID().toString();
    }

    /** 便捷构造：指定 invocation 和超时 */
    public RpcRequest(RpcInvocation invocation, long timeoutMs) {
        this();
        this.invocation = invocation;
        this.timeoutMs = timeoutMs;
    }
}

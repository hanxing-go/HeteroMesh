package com.heteromesh.rpc;


/**
 * RPC 拦截器。参考 Dubbo Filter 和 gRPC Interceptor 设计。
 *
 * 实现类在 intercept() 中通过 chain.next(invocation) 将调用传递给下一个拦截器或最终处理者。
 * 可以在 chain.next() 前后插入横切逻辑（日志、鉴权、限流、指标等）。
 */
public interface RpcInterceptor {
    /**
     * 拦截一次 RPC 调用。
     *
     * @param invocation 本次调用的描述（serviceName + methodName + args）
     * @param chain      拦截器链，调用 chain.next(invocation) 将请求传给下一个节点
     * @return 最终处理者的返回值
     */
    Object intercept(RpcInvocation invocation, RpcInterceptorChain chain) throws Exception;
}

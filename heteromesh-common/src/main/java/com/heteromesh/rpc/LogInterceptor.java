package com.heteromesh.rpc;

import lombok.extern.slf4j.Slf4j;

/**
 * 日志拦截器：在每次 RPC 调用前后记录日志和耗时。
 */
@Slf4j
public class LogInterceptor implements RpcInterceptor{

    @Override
    public Object intercept(RpcInvocation invocation, RpcInterceptorChain chain) throws Exception {
        log.info("[RPC] -> {}.{}() 参数个数={}",
                invocation.getServiceName(),
                invocation.getMethodName(),
                invocation.getArgs() != null ? invocation.getArgs().length : 0);
        long start = System.currentTimeMillis();
        try {
            Object result = chain.next(invocation);
            log.info("[RPC] <- {}.{}() 完成  耗时={}ms",
                    invocation.getServiceName(),
                    invocation.getMethodName(),
                    System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("[RPC] <- {}.{}() 调用失败  耗时={}ms",
                    invocation.getServiceName(),
                    invocation.getMethodName(),
                    System.currentTimeMillis() - start, e);
            throw e;
        }
    }
}

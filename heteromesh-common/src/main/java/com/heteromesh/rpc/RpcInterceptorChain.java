package com.heteromesh.rpc;



import java.util.ArrayList;
import java.util.List;

/**
 * RPC 拦截器链，责任链模式的实现。
 *
 * 链上依次排列拦截器，最后一个节点是最终处理者（handler）。
 * 调用 chain.next(invocation) 开始执行整条链。
 */
public class RpcInterceptorChain {

    private final List<RpcInterceptor> interceptors;
    private final RpcHandler handler;
    private int currentIndex = 0;


    public RpcInterceptorChain(List<RpcInterceptor> interceptors, RpcHandler handler) {
        this.interceptors = interceptors;
        this.handler = handler;
    }

    /**
     * 将调用传递给链上的下一个节点。
     * 如果还有拦截器未执行 → 执行下一个拦截器
     * 如果所有拦截器都已执行 → 交给最终 handler
     */
    public Object next(RpcInvocation invocation) throws Exception {
        if (currentIndex < interceptors.size()) {
            // 逐个取拦截器，索引递增
            RpcInterceptor interceptor = interceptors.get(currentIndex++);
            return interceptor.intercept(invocation, this);
        } else {
            // 全部执行完毕
            return handler.handle(invocation);
        }
    }

    /**
     * 最终处理者：真正执行方法调用的逻辑。
     */
    @FunctionalInterface
    public interface RpcHandler {
        Object handle(RpcInvocation invocation) throws Exception;
    }

    // ===== Builder =====
    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private final List<RpcInterceptor> interceptors = new ArrayList<>();
        private RpcHandler handler;

        public Builder addInterceptor(RpcInterceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        public Builder handler(RpcHandler handler) {
            this.handler = handler;
            return this;
        }

        public RpcInterceptorChain build() {
            return new RpcInterceptorChain(interceptors, handler);
        }
    }
}

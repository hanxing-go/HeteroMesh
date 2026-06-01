package com.heteromesh.rpc;

import com.google.gson.Gson;
import com.heteromesh.transport.RpcClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

public class RpcProxy {
    private static final Gson GSON = new Gson();
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    /**
     * 引用一个远程服务，返回动态代理对象。
     *
     * @param interfaceClass 服务接口的 Class 对象
     * @param rpcClient      底层 RPC 客户端（封装了 Netty Channel + pendingRequests）
     * @return 代理对象，可以强转为 interfaceClass 类型
     */
    @SuppressWarnings("unchecked")
    public static <T> T reference(Class<T> interfaceClass, RpcClient rpcClient) {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                new RpcInvocationHandler(rpcClient, interfaceClass)
        );
    }


    /**
     * 拦截所有代理对象的方法调用，转为 RPC 请求。
     */
    private record RpcInvocationHandler(RpcClient rpcClient, Class<?> interfaceClass) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // ① Object 的方法（toString / equals / hashCode）不走远程
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }

            // ② 构造 RpcInvocation
            RpcInvocation invocation = new RpcInvocation();
            invocation.setServiceName(interfaceClass.getName());
            invocation.setMethodName(method.getName());
            invocation.setParameterTypes(getParameterTypeNames(method));
            invocation.setArgs(args != null ? args : new Object[0]);

            RpcRequest request = new RpcRequest();
            request.setInvocation(invocation);
            request.setTimeoutMs(DEFAULT_TIMEOUT_MS);
            request.setOneWay(method.getReturnType() == void.class);

            // ③ 发送
            String body = GSON.toJson(request);

            // void 方法：发了不管
            if (request.isOneWay()) {
                rpcClient.call(body);
                return null;
            }

            // ④ RpcClient 内部生成 requestId 并返回 RpcFuture
            RpcFuture rpcFuture = rpcClient.call(body, request.getTimeoutMs());

            // 异步方法：返回 CompletableFuture<RpcResponse>
            if (method.getReturnType() == CompletableFuture.class) {
                return rpcFuture.getFuture().thenApply(msg ->
                        GSON.fromJson(msg.getBody(), RpcResponse.class));
            }

            // ⑤ 同步等待
            RpcResponse rpcResponse = rpcFuture.get();

            if (rpcResponse.isSuccess()) {
                return GSON.fromJson(GSON.toJson(rpcResponse.getResult()), method.getReturnType());
            } else {
                throw new RuntimeException("RPC 调用失败:" + rpcResponse.getStatus()
                        + "-" + rpcResponse.getErrorMessage());
            }
        }

        private String[] getParameterTypeNames(Method method) {
            Class<?>[] types = method.getParameterTypes();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) {
                names[i] = types[i].getName();
            }
            return names;
        }
    }
}

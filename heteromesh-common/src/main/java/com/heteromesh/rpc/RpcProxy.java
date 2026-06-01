package com.heteromesh.rpc;

import com.google.gson.Gson;
import com.heteromesh.protocol.Message;
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
            // TODO: 先暂时默认设置
            request.setTimeoutMs(DEFAULT_TIMEOUT_MS);
            request.setOneWay(method.getReturnType() == void.class);

            // ③ 发送 RPC 请求
            String body = GSON.toJson(request);
            CompletableFuture<Message> future = rpcClient.call(body);

            // ④ 处理返回值
            if (request.isOneWay()) {
                // void 方法：不等结果，直接返回 null
                return null;
            }

            if (method.getReturnType() == CompletableFuture.class) {
                // 异步方法：把 CompletableFuture<Message> 转成 CompletableFuture<实际类型>
                return future.thenApply(msg -> {
                    return GSON.fromJson(msg.getBody(), RpcResponse.class);
                });
            }

            // ⑤ 同步方法：用 RpcFuture 等待响应（统一返回 RpcResponse，自动处理超时清理）
            RpcFuture rpcFuture = new RpcFuture(future, request.getRequestId(), rpcClient);
            RpcResponse rpcResponse = rpcFuture.get(request.getTimeoutMs());

            if (rpcResponse.isSuccess()) {
                // 取出 result，反序列化为期望的返回结果
                return GSON.fromJson(GSON.toJson(rpcResponse.getResult()), method.getReturnType());
            } else {
                throw new RuntimeException("RPC 调用失败:" + rpcResponse.getStatus() + "-" + rpcResponse.getErrorMessage());
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

package com.heteromesh.rpc;

import com.google.gson.Gson;
import com.heteromesh.protocol.Message;
import com.heteromesh.transport.RpcClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

            // ③ 发送 RPC 请求
            String body = GSON.toJson(invocation);
            CompletableFuture<Message> future = rpcClient.call(body);

            // ④ 处理返回值
            if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
                // void 方法：不等结果，直接返回 null
                return null;
            }

            if (method.getReturnType() == CompletableFuture.class) {
                // 异步方法：把 CompletableFuture<Message> 转成 CompletableFuture<实际类型>
                return future.thenApply(msg -> {
                    // 这里只能拿到 Message，实际类型需要调用方自己转换
                    // 简化处理：返回 body 原始字符串
                    return msg.getBody();
                });
            }

            // ⑤ 同步方法：阻塞等待，直到拿到响应
            Message response = future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                return null;
            }

            // ⑥ 反序列化返回值
            return GSON.fromJson(responseBody, method.getReturnType());
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

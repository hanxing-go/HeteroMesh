package com.heteromesh.rpc;


import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RPC 调用分发器：根据 RpcInvocation 找到服务实现并反射调用。
 */
@Slf4j
public class RpcDispatcher {

    private static final Gson GSON = new Gson();
    private final RpcServiceRegistry registry;

    // 缓存 Method 对象，避免每次调用都做反射查找
    // key = "serviceName#methodName#paramType1,paramType2,..."
    private final Map<String, Method> methodCache = new ConcurrentHashMap<>();

    public RpcDispatcher(RpcServiceRegistry registry) {
        this.registry = registry;
    }

    /**
     * 分发一个 RPC 调用：查服务 → 匹配方法 → 反射调用 → 返回结果。
     *
     * @param invocationJson TASK_REQUEST body（RpcInvocation 的 JSON）
     * @return 方法返回值（可能为 null），序列化后放入 TASK_RESPONSE
     */
    public Object dispatch(String invocationJson) throws Exception {
        RpcInvocation invocation = GSON.fromJson(invocationJson, RpcInvocation.class);

        // ① 查找服务实现
        Object service = registry.lookup(invocation.getServiceName());
        if (service == null) {
            throw new IllegalArgumentException("服务未找到:" + invocation.getServiceName());
        }

        Method method = getMethod(service.getClass(), invocation);
        // ③ 转换参数类型（Gson 数字类型处理）
        Object[] convertedArgs = convertArgs(invocation.getArgs(), method.getParameterTypes());
        // ④ 反射调用
        log.debug("RPC 调用: {}.{}()",
                invocation.getServiceName(),
                invocation.getMethodName());
        return method.invoke(service, convertedArgs);
    }



    private Method getMethod(Class<?> serviceClass, RpcInvocation invocation)
            throws NoSuchMethodException, ClassNotFoundException{

        String cacheKey = buildCacheKey(invocation);
        Method cached = methodCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String[] typeNames = invocation.getParameterTypes();
        Class<?>[] paramTypes = new Class<?>[typeNames.length];

        for (int i = 0; i < typeNames.length; i++) {
            paramTypes[i] = toClass(typeNames[i]);
        }

        Method method = serviceClass.getMethod(invocation.getMethodName(), paramTypes);
        methodCache.put(cacheKey, method);

        return method;
    }

    private Class<?> toClass(String typeName) throws ClassNotFoundException{
        return switch (typeName) {
            case "int" -> int.class;
            case "long"    -> long.class;
            case "double"  -> double.class;
            case "float"   -> float.class;
            case "boolean" -> boolean.class;
            case "byte"    -> byte.class;
            case "short"   -> short.class;
            case "char"    -> char.class;
            case "void"    -> void.class;
            default        -> Class.forName(typeName);
        };
    }

    private String buildCacheKey(RpcInvocation invocation) {
        return invocation.getServiceName() + "#" + invocation.getMethodName() + "#"
                + String.join(",", invocation.getParameterTypes());
    }

    /**
     * 将参数数组中的值转换为方法签名声明的类型。
     * 主要解决 Gson 将所有数字解析为 Double 的问题。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object[] convertArgs(Object[] args, Class<?>[] parameterTypes) {
        if (args == null || args.length == 0) {
            return args;
        }

        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            converted[i] = convertSingleArg(args[i], parameterTypes[i]);
        }

        return converted;
    }

    private Object convertSingleArg(Object arg, Class<?> parameterType) {
        if (arg == null) {
            return null;
        }

        if (parameterType.isInstance(arg)) return arg;

        // Gson 把 JSON 数字解析为 Double，需要转为目标类型
        if (arg instanceof Number num) {
            if (parameterType == int.class || parameterType == Integer.class) return num.intValue();
            if (parameterType == long.class || parameterType == Long.class) return num.longValue();
            if (parameterType == double.class || parameterType == Double.class) return num.doubleValue();
            if (parameterType == float.class || parameterType == Float.class) return num.floatValue();
            if (parameterType == short.class || parameterType == Short.class) return num.shortValue();
            if (parameterType == byte.class || parameterType == Byte.class) return num.byteValue();
        }

        // String → 枚举
        if (parameterType.isEnum() && arg instanceof String s) {
            return Enum.valueOf((Class<? extends Enum>) parameterType, s);
        }

        // 兜底：用 Gson 的转换
        return GSON.fromJson(GSON.toJson(arg), parameterType);

    }
}

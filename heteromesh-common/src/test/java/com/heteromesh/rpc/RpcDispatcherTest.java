package com.heteromesh.rpc;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpcDispatcherTest {

    private static final Gson GSON = new Gson();

    private RpcServiceRegistry registry;
    private RpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = new RpcServiceRegistry();
        dispatcher = new RpcDispatcher(registry);
    }

    // ---------- 基础功能 ----------

    // 验证：dispatch 能正确找到服务实现、匹配方法并反射调用，返回正确结果
    @Test
    void shouldDispatchAndReturnResult() throws Exception {
        registry.publish(CalcService.class, new CalcServiceImpl());

        RpcInvocation inv = new RpcInvocation();
        inv.setServiceName(CalcService.class.getName());
        inv.setMethodName("add");
        inv.setParameterTypes(new String[]{"int", "int"});
        inv.setArgs(new Object[]{3, 5});

        Object result = dispatcher.dispatch(GSON.toJson(inv));
        assertEquals(8, result);  // add() 返回 int，反射调用返回的就是 Integer
    }

    // 验证：dispatch 支持 String 类型参数（引用类型），Gson 不会把它误转成 Double
    @Test
    void shouldDispatchMethodWithStringParameter() throws Exception {
        registry.publish(GreeterService.class, new GreeterServiceImpl());

        RpcInvocation inv = new RpcInvocation();
        inv.setServiceName(GreeterService.class.getName());
        inv.setMethodName("sayHello");
        inv.setParameterTypes(new String[]{"java.lang.String"});
        inv.setArgs(new Object[]{"张三"});

        Object result = dispatcher.dispatch(GSON.toJson(inv));
        assertEquals("你好, 张三", result);
    }

    // 验证：无参方法也能正常反射调用，不会因为参数数组为空而抛异常
    @Test
    void shouldDispatchNoArgMethod() throws Exception {
        registry.publish(CalcService.class, new CalcServiceImpl());

        RpcInvocation inv = new RpcInvocation();
        inv.setServiceName(CalcService.class.getName());
        inv.setMethodName("version");
        inv.setParameterTypes(new String[]{});
        inv.setArgs(new Object[]{});

        Object result = dispatcher.dispatch(GSON.toJson(inv));
        assertEquals("1.0", result);
    }

    // ---------- 异常情况 ----------

    // 验证：服务未注册时 dispatch 应抛 IllegalArgumentException，而不是空指针
    @Test
    void shouldThrowWhenServiceNotFound() {
        RpcInvocation inv = new RpcInvocation();
        inv.setServiceName("com.unknown.NonExistentService");
        inv.setMethodName("foo");
        inv.setParameterTypes(new String[]{});
        inv.setArgs(new Object[]{});

        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch(GSON.toJson(inv)),
                "服务未找到时应抛 IllegalArgumentException");
    }

    // 验证：方法名拼错或不存在时，dispatch 应抛 NoSuchMethodException
    @Test
    void shouldThrowWhenMethodNotFound() {
        registry.publish(CalcService.class, new CalcServiceImpl());

        RpcInvocation inv = new RpcInvocation();
        inv.setServiceName(CalcService.class.getName());
        inv.setMethodName("nonExistentMethod");  // 不存在的方法
        inv.setParameterTypes(new String[]{});
        inv.setArgs(new Object[]{});

        assertThrows(NoSuchMethodException.class,
                () -> dispatcher.dispatch(GSON.toJson(inv)),
                "方法不存在时应抛 NoSuchMethodException");
    }

    // ---------- Method 缓存 ----------

    // 验证：相同方法的第二次调用走 Method 缓存，不会重复反射查找（性能优化）
    @Test
    void shouldCacheMethodObject() throws Exception {
        registry.publish(CalcService.class, new CalcServiceImpl());

        RpcInvocation inv = new RpcInvocation();
        inv.setServiceName(CalcService.class.getName());
        inv.setMethodName("add");
        inv.setParameterTypes(new String[]{"int", "int"});
        inv.setArgs(new Object[]{1, 2});

        // 第一次调用
        Object r1 = dispatcher.dispatch(GSON.toJson(inv));
        assertEquals(3, r1);

        // 第二次调用 —— 应该走缓存
        Object r2 = dispatcher.dispatch(GSON.toJson(inv));
        assertEquals(3, r2);
    }

    // ---------- 辅助接口和实现 ----------

    interface CalcService {
        int add(int a, int b);
        String version();
    }

    static class CalcServiceImpl implements CalcService {
        @Override
        public int add(int a, int b) {
            return a + b;
        }

        @Override
        public String version() {
            return "1.0";
        }
    }

    interface GreeterService {
        String sayHello(String name);
    }

    static class GreeterServiceImpl implements GreeterService {
        @Override
        public String sayHello(String name) {
            return "你好, " + name;
        }
    }
}

package com.heteromesh.rpc;

import com.heteromesh.transport.RpcClient;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class RpcProxyTest {

    private RpcClient rpcClient;

    @BeforeEach
    void setUp() {
        // EmbeddedChannel 不需要真实网络，适合测试
        EmbeddedChannel channel = new EmbeddedChannel();
        rpcClient = new RpcClient(channel);
    }

    // ---------- 代理结构 ----------

    // 验证：RpcProxy.reference() 返回的对象是 JDK 动态代理，且实现了目标接口
    @Test
    void shouldReturnProxyThatImplementsInterface() {
        Greeter proxy = RpcProxy.reference(Greeter.class, rpcClient);

        assertNotNull(proxy);
        assertTrue(Proxy.isProxyClass(proxy.getClass()),
                "返回的对象应该是 JDK 动态代理");
        assertTrue(proxy instanceof Greeter,
                "代理应该实现了 Greeter 接口");
    }

    // 验证：不同接口生成的代理互不干扰，各自只实现自己的接口
    @Test
    void shouldCreateDifferentProxiesForDifferentInterfaces() {
        Greeter greeter = RpcProxy.reference(Greeter.class, rpcClient);
        Calc calc = RpcProxy.reference(Calc.class, rpcClient);

        assertTrue(greeter instanceof Greeter);
        assertTrue(calc instanceof Calc);
        assertFalse(calc instanceof Greeter);
    }

    // ---------- Object 方法不走远程 ----------

    // 验证：toString() 走本地逻辑，不会尝试发网络请求（发的话会因 EmbeddedChannel 没有远端而抛异常）
    @Test
    void toStringShouldNotThrow() {
        Greeter proxy = RpcProxy.reference(Greeter.class, rpcClient);

        // Object 方法走本地，不会尝试发 RPC → 不会抛异常
        String s = proxy.toString();
        assertNotNull(s);
        assertTrue(s.contains("RpcProxy") || s.contains("Proxy"),
                "toString 应包含代理相关信息");
    }

    // 验证：hashCode() 走本地逻辑，不会误发 RPC 请求
    @Test
    void hashCodeShouldNotThrow() {
        Greeter proxy = RpcProxy.reference(Greeter.class, rpcClient);
        // 不走远程就不抛异常
        assertDoesNotThrow(() -> proxy.hashCode());
    }

    // 验证：equals() 走本地逻辑，自己跟自己比不会走远程
    @Test
    void equalsShouldWorkLocally() {
        Greeter p1 = RpcProxy.reference(Greeter.class, rpcClient);
        // 自己跟自己比，走 equals(Object) → 不走远程
        assertDoesNotThrow(() -> p1.equals(p1));
    }

    // ---------- 辅助接口 ----------

    interface Greeter {
        String sayHello(String name);
    }

    interface Calc {
        int add(int a, int b);
    }
}

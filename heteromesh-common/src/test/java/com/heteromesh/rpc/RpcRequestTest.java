package com.heteromesh.rpc;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpcRequestTest {

    private static final Gson GSON = new Gson();

    // 验证 RpcRequest 不包含 requestId（requestId 是传输层的事）
    @Test
    void shouldNotHaveRequestIdField() {
        RpcRequest request = new RpcRequest();

        // 用 Gson 序列化后，JSON 里应该没有 requestId
        request.setInvocation(createInvocation());
        String json = GSON.toJson(request);

        assertFalse(json.contains("requestId"),
                "RpcRequest JSON 不应包含 requestId，它是传输层的概念");
    }

    // 验证序列化/反序列化往返
    @Test
    void shouldSerializeAndDeserializeRoundTrip() {
        RpcRequest original = new RpcRequest();
        RpcInvocation invocation = createInvocation();
        original.setInvocation(invocation);
        original.setTimeoutMs(5000);
        original.setOneWay(false);

        String json = GSON.toJson(original);
        RpcRequest restored = GSON.fromJson(json, RpcRequest.class);

        assertEquals(5000, restored.getTimeoutMs());
        assertFalse(restored.isOneWay());
        assertEquals(invocation.getServiceName(), restored.getInvocation().getServiceName());
        assertEquals(invocation.getMethodName(), restored.getInvocation().getMethodName());
        assertEquals(invocation.getParameterTypes().length, restored.getInvocation().getParameterTypes().length);
        assertEquals(invocation.getArgs().length, restored.getInvocation().getArgs().length);
    }

    // 验证默认值
    @Test
    void shouldHaveSensibleDefaults() {
        RpcRequest request = new RpcRequest();

        assertEquals(0, request.getTimeoutMs(), "默认超时应为 0（不限制）");
        assertFalse(request.isOneWay(), "默认应为双向调用");
        assertNull(request.getInvocation(), "invocation 默认为 null（需调用方设置）");
    }

    // 验证 oneWay 标志
    @Test
    void oneWayShouldBeSettable() {
        RpcRequest request = new RpcRequest();
        request.setOneWay(true);
        assertTrue(request.isOneWay());
        request.setOneWay(false);
        assertFalse(request.isOneWay());
    }

    // 验证 timeoutMs
    @Test
    void timeoutMsShouldBeSettable() {
        RpcRequest request = new RpcRequest();
        request.setTimeoutMs(3000);
        assertEquals(3000, request.getTimeoutMs());
    }

    private RpcInvocation createInvocation() {
        RpcInvocation inv = new RpcInvocation();
        inv.setServiceName("com.heteromesh.demo.TestService");
        inv.setMethodName("process");
        inv.setParameterTypes(new String[]{"java.lang.String", "int"});
        inv.setArgs(new Object[]{"data.txt", 5});
        return inv;
    }
}

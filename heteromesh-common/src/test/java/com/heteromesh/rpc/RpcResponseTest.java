package com.heteromesh.rpc;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpcResponseTest {

    private static final Gson GSON = new Gson();

    // ---------- 静态工厂方法 ----------

    @Test
    void successShouldSetStatusAndResult() {
        RpcResponse resp = RpcResponse.success("处理完成");

        assertEquals(RpcStatus.SUCCESS, resp.getStatus());
        assertEquals("处理完成", resp.getResult());
        assertNull(resp.getErrorMessage());
        assertTrue(resp.isSuccess());
    }

    @Test
    void successShouldAcceptNullResult() {
        RpcResponse resp = RpcResponse.success(null);

        assertEquals(RpcStatus.SUCCESS, resp.getStatus());
        assertNull(resp.getResult());
        assertTrue(resp.isSuccess(), "方法返回 null 也是成功");
    }

    @Test
    void errorShouldSetStatusAndMessage() {
        RpcResponse resp = RpcResponse.error(RpcStatus.NOT_FOUND, "服务未找到");

        assertEquals(RpcStatus.NOT_FOUND, resp.getStatus());
        assertNull(resp.getResult());
        assertEquals("服务未找到", resp.getErrorMessage());
        assertFalse(resp.isSuccess());
    }

    @Test
    void timeoutShouldUseTimeoutStatus() {
        RpcResponse resp = RpcResponse.timeout("等待 5000ms 超时");

        assertEquals(RpcStatus.TIMEOUT, resp.getStatus());
        assertEquals("等待 5000ms 超时", resp.getErrorMessage());
        assertFalse(resp.isSuccess());
    }

    // ---------- isSuccess ----------

    @Test
    void isSuccessShouldReturnTrueOnlyForSuccess() {
        assertTrue(RpcResponse.success("ok").isSuccess());
        assertFalse(RpcResponse.error(RpcStatus.ERROR, "err").isSuccess());
        assertFalse(RpcResponse.timeout("timeout").isSuccess());
        assertFalse(RpcResponse.error(RpcStatus.NOT_FOUND, "nf").isSuccess());
        assertFalse(RpcResponse.error(RpcStatus.FRAMEWORK_ERROR, "fe").isSuccess());
    }

    // ---------- JSON 序列化往返 ----------

    @Test
    void shouldSerializeAndDeserializeSuccessResponse() {
        RpcResponse original = RpcResponse.success("Hello");

        String json = GSON.toJson(original);
        RpcResponse restored = GSON.fromJson(json, RpcResponse.class);

        assertEquals(RpcStatus.SUCCESS, restored.getStatus());
        assertEquals("Hello", restored.getResult());
        assertTrue(restored.isSuccess());
    }

    @Test
    void shouldSerializeAndDeserializeErrorResponse() {
        RpcResponse original = RpcResponse.error(RpcStatus.NOT_FOUND, "服务不存在");

        String json = GSON.toJson(original);
        RpcResponse restored = GSON.fromJson(json, RpcResponse.class);

        assertEquals(RpcStatus.NOT_FOUND, restored.getStatus());
        assertEquals("服务不存在", restored.getErrorMessage());
        assertFalse(restored.isSuccess());
    }

    @Test
    void shouldSerializeAndDeserializeTimeoutResponse() {
        RpcResponse original = RpcResponse.timeout("超时了");

        String json = GSON.toJson(original);
        RpcResponse restored = GSON.fromJson(json, RpcResponse.class);

        assertEquals(RpcStatus.TIMEOUT, restored.getStatus());
        assertFalse(restored.isSuccess());
    }
}

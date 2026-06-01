package com.heteromesh.rpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpcStatusTest {

    // 验证每个枚举值有唯一的 code
    @Test
    void shouldHaveUniqueCodes() {
        RpcStatus[] values = RpcStatus.values();
        assertEquals(6, values.length, "应该有 6 种状态");

        long distinctCodes = java.util.Arrays.stream(values)
                .mapToInt(RpcStatus::getCode)
                .distinct()
                .count();
        assertEquals(values.length, distinctCodes, "每个状态的 code 应该唯一");
    }

    // 验证 code 可以从 0 开始连续赋值
    @Test
    void shouldHaveCorrectCodes() {
        assertEquals(0, RpcStatus.SUCCESS.getCode());
        assertEquals(1, RpcStatus.TIMEOUT.getCode());
        assertEquals(2, RpcStatus.NOT_FOUND.getCode());
        assertEquals(3, RpcStatus.METHOD_NOT_FOUND.getCode());
        assertEquals(4, RpcStatus.ERROR.getCode());
        assertEquals(5, RpcStatus.FRAMEWORK_ERROR.getCode());
    }
}

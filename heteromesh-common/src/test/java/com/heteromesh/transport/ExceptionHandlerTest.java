package com.heteromesh.transport;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionHandlerTest {

    @Test
    void shouldCloseChannelOnIOException() {
        EmbeddedChannel channel = new EmbeddedChannel(new ExceptionHandler());

        channel.pipeline().fireExceptionCaught(
                new IOException("Connection reset by peer"));

        assertFalse(channel.isOpen(), "IOException 时应该关闭 Channel");
    }

    @Test
    void shouldCloseChannelOnRuntimeException() {
        EmbeddedChannel channel = new EmbeddedChannel(new ExceptionHandler());

        channel.pipeline().fireExceptionCaught(
                new RuntimeException("协议解析失败"));

        assertFalse(channel.isOpen(), "非 IOException 异常时也应该关闭 Channel");
    }

    @Test
    void shouldCloseChannelOnNullPointerException() {
        EmbeddedChannel channel = new EmbeddedChannel(new ExceptionHandler());

        channel.pipeline().fireExceptionCaught(
                new NullPointerException("msg is null"));

        assertFalse(channel.isOpen(), "任何异常都应该关闭 Channel");
    }

    @Test
    void shouldNotThrowWhenExceptionCaughtCalled() {
        // 验证 ExceptionHandler 不会把异常再往外抛
        EmbeddedChannel channel = new EmbeddedChannel(new ExceptionHandler());

        assertDoesNotThrow(() -> {
            channel.pipeline().fireExceptionCaught(new IOException("测试"));
        }, "exceptionCaught 不应该再往外抛异常");
    }
}

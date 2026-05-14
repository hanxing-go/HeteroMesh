package com.heteromesh.transport;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatHandlerTest {

    @Test
    void shouldReplyPongWhenReceivingPing() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        channel.writeInbound(Message.createPing());

        Message reply = channel.readOutbound();
        assertNotNull(reply, "收到 PING 应该回复 PONG");
        assertEquals(MessageType.PONG, reply.getType());
        assertFalse(channel.finish());
    }

    @Test
    void shouldNotForwardPingToNextHandler() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        channel.writeInbound(Message.createPing());

        // PING 被吃掉了，下一个 Handler 不会收到
        Message forwarded = channel.readInbound();
        assertNull(forwarded, "PING 不应该传给下游 Handler");

        // PING 触发了 PONG 回复，需要消费掉
        Message pongReply = channel.readOutbound();
        assertNotNull(pongReply, "收到 PING 应该回复 PONG");
        assertEquals(MessageType.PONG, pongReply.getType());

        assertFalse(channel.finish());
    }

    @Test
    void shouldNotForwardPongToNextHandler() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        channel.writeInbound(Message.createPong());

        Message forwarded = channel.readInbound();
        assertNull(forwarded, "PONG 不应该传给下游 Handler");
        assertFalse(channel.finish());
    }

    @Test
    void shouldForwardTaskRequestToNextHandler() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        channel.writeInbound(Message.createTaskRequest("测试任务"));

        Message forwarded = channel.readInbound();
        assertNotNull(forwarded, "非心跳消息应该传给下游");
        assertEquals(MessageType.TASK_REQUEST, forwarded.getType());
        assertEquals("测试任务", forwarded.getBody());
        assertFalse(channel.finish());
    }

    @Test
    void shouldSendPingOnAllIdleEvent() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        channel.pipeline().fireUserEventTriggered(
                IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);

        Message ping = channel.readOutbound();
        assertNotNull(ping, "ALL_IDLE 事件应该触发发送 PING");
        assertEquals(MessageType.PING, ping.getType());
        assertFalse(channel.finish());
    }

    @Test
    void shouldCloseChannelAfterThreeConsecutiveIdleEvents() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());
        IdleStateEvent allIdle = IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT;

        // 3 次空闲 → 发 3 条 PING，无人应答，计数器升至 3
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        assertTrue(channel.isOpen(), "发了 3 条 PING 后 Channel 应该仍开着");

        // 第 4 次空闲，计数器 >= 3，判定离线
        channel.pipeline().fireUserEventTriggered(allIdle);

        assertFalse(channel.isOpen(), "连续 3 次无应答后应该关闭 Channel");
    }

    @Test
    void shouldResetCounterWhenPongReceived() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());
        IdleStateEvent allIdle = IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT;

        // 2 次空闲 → 发 2 条 PING
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        // 收到 PONG → 计数器归零
        channel.writeInbound(Message.createPong());

        // 再发 3 次空闲 → 计数器重新从 0 开始，不会关
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        assertTrue(channel.isOpen(), "收到 PONG 后计数器应归零，不会累积到 3");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldResetCounterWhenPingReceived() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());
        IdleStateEvent allIdle = IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT;

        // 2 次空闲
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        // 收到对方的 PING 探活 → 说明连接仍是好的，计数器归零
        channel.writeInbound(Message.createPing());

        // 再发 3 次空闲
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        assertTrue(channel.isOpen(), "收到 PING 也说明连接存活，计数器应归零");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldIgnoreNonIdleStateEvents() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        // 传一个非 IdleStateEvent 的普通事件，不应抛异常
        channel.pipeline().fireUserEventTriggered("普通事件");

        // Channel 仍应存活，且没有乱发消息
        assertTrue(channel.isOpen());
        Message out = channel.readOutbound();
        assertNull(out, "非 IdleStateEvent 不应触发任何行为");
        assertFalse(channel.finish());
    }
}

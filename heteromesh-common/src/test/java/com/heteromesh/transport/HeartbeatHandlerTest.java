package com.heteromesh.transport;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatHandlerTest {

    // 收到 PING → 回复 PONG
    @Test
    void shouldReplyPongWhenReceivingPing() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        channel.writeInbound(Message.createPing());

        Message reply = channel.readOutbound();
        assertNotNull(reply, "收到 PING 应该回复 PONG");
        assertEquals(MessageType.PONG, reply.getType());
        assertFalse(channel.finish());
    }

    // 收到 PING → 回复 PONG + 透传 PING 给下游（供 ServerHandler 更新 lastHeartbeat）
    @Test
    void shouldForwardPingToNextHandler() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        channel.writeInbound(Message.createPing());

        Message forwarded = channel.readInbound();
        assertNotNull(forwarded, "PING 应该透传给下游 Handler");
        assertEquals(MessageType.PING, forwarded.getType());

        Message pongReply = channel.readOutbound();
        assertNotNull(pongReply, "收到 PING 应该回复 PONG");
        assertEquals(MessageType.PONG, pongReply.getType());

        assertFalse(channel.finish());
    }

    // 收到 PONG → 透传 PONG 给下游（供 ServerHandler 更新 lastHeartbeat）
    @Test
    void shouldForwardPongToNextHandler() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        channel.writeInbound(Message.createPong());

        Message forwarded = channel.readInbound();
        assertNotNull(forwarded, "PONG 应该透传给下游 Handler");
        assertEquals(MessageType.PONG, forwarded.getType());
        assertFalse(channel.finish());
    }

    // 非心跳消息（TASK_REQUEST）照样透传
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

    // IdleStateHandler 触发 ALL_IDLE → 发送 PING
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

    // 连续 3 次空闲无应答 → 第 4 次关闭 Channel
    @Test
    void shouldCloseChannelAfterThreeConsecutiveIdleEvents() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());
        IdleStateEvent allIdle = IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT;

        // 3 次空闲 → 计数器升至 3
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        assertTrue(channel.isOpen(), "发了 3 条 PING 后 Channel 应该仍开着");

        // 第 4 次空闲，计数器 >= 3，判定离线
        channel.pipeline().fireUserEventTriggered(allIdle);

        assertFalse(channel.isOpen(), "连续 3 次无应答后应该关闭 Channel");
    }

    // 收到 PONG → 计数器归零，不会误判断连
    @Test
    void shouldResetCounterWhenPongReceived() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());
        IdleStateEvent allIdle = IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT;

        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        // 收到 PONG → 计数器归零
        channel.writeInbound(Message.createPong());

        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        assertTrue(channel.isOpen(), "收到 PONG 后计数器应归零，不会累积到 3");
        channel.finishAndReleaseAll();
    }

    // 收到 PING → 计数器归零，对方的探活也能证明连接正常
    @Test
    void shouldResetCounterWhenPingReceived() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());
        IdleStateEvent allIdle = IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT;

        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        channel.writeInbound(Message.createPing());

        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);
        channel.pipeline().fireUserEventTriggered(allIdle);

        assertTrue(channel.isOpen(), "收到 PING 也说明连接存活，计数器应归零");
        channel.finishAndReleaseAll();
    }

    // 非 IdleStateEvent 事件直接忽略，不抛异常
    @Test
    void shouldIgnoreNonIdleStateEvents() {
        EmbeddedChannel channel = new EmbeddedChannel(new HeartbeatHandler());

        channel.pipeline().fireUserEventTriggered("普通事件");

        assertTrue(channel.isOpen());
        Message out = channel.readOutbound();
        assertNull(out, "非 IdleStateEvent 不应触发任何行为");
        assertFalse(channel.finish());
    }
}

package com.heteromesh.controller.node;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeChannelMapTest {

    private NodeChannelMap map;

    @BeforeEach
    void setUp() {
        map = new NodeChannelMap();
    }

    // bind 后通过 nodeId 能查到 Channel
    @Test
    void shouldBindAndGetChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        map.bind("worker-01", channel);

        assertEquals(channel, map.getChannel("worker-01"));
        assertEquals(1, map.size());
    }

    // bind 后通过 Channel 能反查 nodeId
    @Test
    void shouldBindAndGetNodeId() {
        EmbeddedChannel channel = new EmbeddedChannel();
        map.bind("worker-01", channel);

        assertEquals("worker-01", map.getNodeId(channel));
    }

    // unbind 后两个方向都查不到
    @Test
    void shouldUnbind() {
        EmbeddedChannel channel = new EmbeddedChannel();
        map.bind("worker-01", channel);
        map.unbind(channel);

        assertNull(map.getChannel("worker-01"));
        assertNull(map.getNodeId(channel));
        assertEquals(0, map.size());
    }

    // 查询不存在的 nodeId 返回 null
    @Test
    void shouldReturnNullForUnknownNode() {
        assertNull(map.getChannel("no-such-node"));
    }

    // 未绑定的 Channel 反查返回 null
    @Test
    void shouldReturnNullForUnknownChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        assertNull(map.getNodeId(channel));
    }

    // 同一 nodeId 重复 bind 会覆盖旧 Channel
    @Test
    void shouldOverwriteWhenBindingDuplicateNodeId() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        EmbeddedChannel ch2 = new EmbeddedChannel();

        map.bind("worker-01", ch1);
        map.bind("worker-01", ch2);

        assertEquals(ch2, map.getChannel("worker-01"));
        assertNull(map.getNodeId(ch1));
        assertEquals("worker-01", map.getNodeId(ch2));
        assertEquals(1, map.size());
    }
}

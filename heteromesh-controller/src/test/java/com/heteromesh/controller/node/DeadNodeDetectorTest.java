package com.heteromesh.controller.node;

import com.heteromesh.registry.InMemoryServiceRegistry;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeadNodeDetectorTest {

    private ServiceRegistry registry;
    private NodeChannelMap nodeChannelMap;
    private DeadNodeDetector detector;

    @BeforeEach
    void setUp() {
        registry = new InMemoryServiceRegistry();
        nodeChannelMap = new NodeChannelMap();
        detector = new DeadNodeDetector(registry, nodeChannelMap, 5000);
    }

    // 心跳超时的节点从注册中心移除，映射也解绑
    @Test
    void shouldRemoveDeadNode() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ServiceInstance deadNode = new ServiceInstance(
                "dead-worker", "10.0.0.1", 9090, "CPU", 0,
                System.currentTimeMillis() - 10000,
                System.currentTimeMillis() - 10000);

        registry.register(deadNode);
        nodeChannelMap.bind("dead-worker", channel);

        assertEquals(1, registry.size());

        detector.scan();

        assertEquals(0, registry.size());
        assertNull(registry.lookup("dead-worker"));
        assertNull(nodeChannelMap.getChannel("dead-worker"));
    }

    // 心跳正常的节点扫描后仍然保留
    @Test
    void shouldKeepAliveNode() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ServiceInstance aliveNode = new ServiceInstance(
                "alive-worker", "10.0.0.2", 9090, "RTX 5090", 24 * 1024,
                System.currentTimeMillis(),
                System.currentTimeMillis());

        registry.register(aliveNode);
        nodeChannelMap.bind("alive-worker", channel);

        detector.scan();

        assertEquals(1, registry.size());
        assertNotNull(registry.lookup("alive-worker"));
        assertNotNull(nodeChannelMap.getChannel("alive-worker"));
    }

    // 踢出超时节点时同时关闭其 Channel
    @Test
    void shouldCloseChannelWhenRemovingDeadNode() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ServiceInstance deadNode = new ServiceInstance(
                "dead-worker", "10.0.0.1", 9090, "CPU", 0,
                System.currentTimeMillis() - 10000,
                System.currentTimeMillis() - 10000);

        registry.register(deadNode);
        nodeChannelMap.bind("dead-worker", channel);
        assertTrue(channel.isOpen());

        detector.scan();

        assertFalse(channel.isOpen());
    }

    // 混合场景：存活的保留，超时的踢出，互不影响
    @Test
    void shouldOnlyRemoveExpiredNodes() {
        EmbeddedChannel aliveCh = new EmbeddedChannel();
        EmbeddedChannel deadCh = new EmbeddedChannel();

        ServiceInstance aliveNode = new ServiceInstance(
                "alive", "10.0.0.1", 9090, "CPU", 0,
                System.currentTimeMillis(), System.currentTimeMillis());
        ServiceInstance deadNode = new ServiceInstance(
                "dead", "10.0.0.2", 9090, "CPU", 0,
                System.currentTimeMillis() - 10000, System.currentTimeMillis() - 10000);

        registry.register(aliveNode);
        nodeChannelMap.bind("alive", aliveCh);
        registry.register(deadNode);
        nodeChannelMap.bind("dead", deadCh);

        assertEquals(2, registry.size());

        detector.scan();

        assertEquals(1, registry.size());
        assertNotNull(registry.lookup("alive"));
        assertNull(registry.lookup("dead"));
        assertTrue(aliveCh.isOpen());
        assertFalse(deadCh.isOpen());
    }
}

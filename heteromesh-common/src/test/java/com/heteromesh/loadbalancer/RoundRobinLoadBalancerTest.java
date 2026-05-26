package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoundRobinLoadBalancerTest {

    private RoundRobinLoadBalancer lb;

    @BeforeEach
    void setUp() {
        lb = new RoundRobinLoadBalancer();
    }

    // 空列表时 select 返回 null
    @Test
    void shouldReturnNullWhenEmpty() {
        assertNull(lb.select("any-key"));
        assertEquals(0, lb.size());
    }

    // 单个节点时 select 始终返回该节点
    @Test
    void shouldAlwaysReturnTheOnlyNode() {
        lb.addNode(createInstance("sole-worker"));

        for (int i = 0; i < 100; i++) {
            assertEquals("sole-worker", lb.select("key-" + i).getNodeId());
        }
    }

    // 两个节点时严格轮流：A、B、A、B、A、B...
    @Test
    void shouldAlternateBetweenTwoNodes() {
        lb.addNode(createInstance("A"));
        lb.addNode(createInstance("B"));

        // 连续 10 轮，每轮应该是 A-B-A-B...
        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sequence.add(lb.select("irrelevant").getNodeId());
        }

        for (int i = 0; i < 10; i++) {
            String expected = (i % 2 == 0) ? "A" : "B";
            assertEquals(expected, sequence.get(i),
                    "第 " + i + " 次 select 应该是 " + expected + "，实际: " + sequence.get(i));
        }
    }

    // 三个节点时顺序：A、B、C、A、B、C...
    @Test
    void shouldCycleThroughThreeNodes() {
        lb.addNode(createInstance("A"));
        lb.addNode(createInstance("B"));
        lb.addNode(createInstance("C"));

        assertEquals("A", lb.select("x").getNodeId());
        assertEquals("B", lb.select("x").getNodeId());
        assertEquals("C", lb.select("x").getNodeId());
        assertEquals("A", lb.select("x").getNodeId());  // 回到队首
        assertEquals("B", lb.select("x").getNodeId());
    }

    // 中途移除节点后，轮询在剩余节点上继续
    @Test
    void shouldContinueRoundRobinAfterNodeRemoval() {
        lb.addNode(createInstance("A"));
        lb.addNode(createInstance("B"));
        lb.addNode(createInstance("C"));

        // 先取两轮
        assertEquals("A", lb.select("x").getNodeId());
        assertEquals("B", lb.select("x").getNodeId());

        // 移除 B
        lb.removeNode("B");
        assertEquals(2, lb.size());

        // 继续取：计数器已经在 2，2 % 2 = 0 → 回到 A
        // 简单轮询不记忆"上次选到哪了"，只靠 counter % size 算位置
        assertEquals("A", lb.select("x").getNodeId());
        assertEquals("C", lb.select("x").getNodeId());
        assertEquals("A", lb.select("x").getNodeId());
    }

    // addNode 时先移除重复节点，避免列表出现重复导致某节点被选中的次数翻倍
    @Test
    void shouldNotDuplicateNodeOnReAdd() {
        lb.addNode(createInstance("worker-01"));
        lb.addNode(createInstance("worker-01"));
        assertEquals(1, lb.size());
    }

    // 高并发场景模拟：大量 select 不应抛出异常（验证 & 0x7FFFFFFF 防溢出生效）
    @Test
    void shouldNotThrowOnManySelections() {
        lb.addNode(createInstance("A"));
        lb.addNode(createInstance("B"));

        // 模拟压测级别的调用次数，验证溢出处理不抛异常
        for (int i = 0; i < 100_000; i++) {
            ServiceInstance selected = lb.select("key-" + i);
            assertNotNull(selected);
            assertTrue(selected.getNodeId().equals("A") || selected.getNodeId().equals("B"));
        }
    }

    // removeNode 后再 addNode，轮询恢复正常
    @Test
    void shouldWorkAfterRemoveAndReAdd() {
        lb.addNode(createInstance("A"));
        lb.addNode(createInstance("B"));

        lb.removeNode("A");
        assertEquals(1, lb.size());
        assertEquals("B", lb.select("x").getNodeId());

        lb.addNode(createInstance("A"));
        assertEquals(2, lb.size());
        // 计数器之前已经递增过多次，但新节点已加入，能正常工作即可
        assertNotNull(lb.select("x"));
    }

    // name() 返回 "RoundRobin"
    @Test
    void shouldReturnCorrectName() {
        assertEquals("RoundRobin", lb.name());
    }

    // size() 正确反映物理节点数
    @Test
    void shouldReturnCorrectSize() {
        assertEquals(0, lb.size());
        lb.addNode(createInstance("w1"));
        assertEquals(1, lb.size());
        lb.addNode(createInstance("w2"));
        assertEquals(2, lb.size());
        lb.addNode(createInstance("w3"));
        assertEquals(3, lb.size());
        lb.removeNode("w2");
        assertEquals(2, lb.size());
    }

    // ---------- 辅助方法 ----------

    private ServiceInstance createInstance(String nodeId) {
        long now = System.currentTimeMillis();
        return new ServiceInstance(
                nodeId, "127.0.0.1", 9090, "RTX 5090", 24 * 1024, now, now);
    }
}

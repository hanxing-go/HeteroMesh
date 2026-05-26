package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RandomLoadBalancerTest {

    private RandomLoadBalancer lb;

    @BeforeEach
    void setUp() {
        lb = new RandomLoadBalancer();
    }

    // 空列表时 select 返回 null
    @Test
    void shouldReturnNullWhenEmpty() {
        assertNull(lb.select("any-key"));
        assertEquals(0, lb.size());
    }

    // 单个节点时 select 始终返回该节点
    @Test
    void shouldSelectTheOnlyNode() {
        ServiceInstance node = createInstance("worker-01");
        lb.addNode(node);

        for (int i = 0; i < 100; i++) {
            ServiceInstance selected = lb.select("key-" + i);
            assertNotNull(selected);
            assertEquals("worker-01", selected.getNodeId());
        }
        assertEquals(1, lb.size());
    }

    // 多个节点时 select 会覆盖所有节点（统计上不可能永远只选到一个）
    @Test
    void shouldHitAllNodesEventually() {
        lb.addNode(createInstance("A"));
        lb.addNode(createInstance("B"));
        lb.addNode(createInstance("C"));

        Set<String> hitNodes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            hitNodes.add(lb.select("key-" + i).getNodeId());
        }

        assertEquals(3, hitNodes.size(), "1000 次随机选择应覆盖全部 3 个节点");
    }

    // addNode 时先移除重复的 nodeId，保证列表中不会有重复条目
    @Test
    void shouldNotDuplicateNodeOnReAdd() {
        lb.addNode(createInstance("worker-01"));
        lb.addNode(createInstance("worker-01"));  // 重复添加
        assertEquals(1, lb.size());
    }

    // removeNode 后该节点不会再被选中
    @Test
    void shouldNotReturnRemovedNode() {
        lb.addNode(createInstance("A"));
        lb.addNode(createInstance("B"));
        lb.addNode(createInstance("C"));

        lb.removeNode("B");
        assertEquals(2, lb.size());

        for (int i = 0; i < 500; i++) {
            assertNotEquals("B", lb.select("key-" + i).getNodeId(),
                    "已移除的节点不应被选中");
        }
    }

    // removeNode 后再 addNode 同一个 id，节点恢复正常
    @Test
    void shouldWorkAfterRemoveAndReAdd() {
        lb.addNode(createInstance("worker-01"));
        lb.removeNode("worker-01");
        assertEquals(0, lb.size());

        lb.addNode(createInstance("worker-01"));
        assertEquals(1, lb.size());
        assertEquals("worker-01", lb.select("key").getNodeId());
    }

    // name() 返回 "random"
    @Test
    void shouldReturnCorrectName() {
        assertEquals("random", lb.name());
    }

    // size() 正确反映物理节点数
    @Test
    void shouldReturnCorrectSize() {
        assertEquals(0, lb.size());
        lb.addNode(createInstance("w1"));
        assertEquals(1, lb.size());
        lb.addNode(createInstance("w2"));
        assertEquals(2, lb.size());
        lb.removeNode("w1");
        assertEquals(1, lb.size());
    }

    // 3000 次随机选择，3 个节点的分布偏差不超过 20%
    @Test
    void shouldDistributeEvenly() {
        lb.addNode(createInstance("A"));
        lb.addNode(createInstance("B"));
        lb.addNode(createInstance("C"));

        Map<String, Integer> counters = new HashMap<>();
        int total = 3000;
        for (int i = 0; i < total; i++) {
            String id = lb.select("key-" + i).getNodeId();
            counters.merge(id, 1, Integer::sum);
        }

        int expected = total / 3;
        double maxDeviation = 0.20;
        for (Map.Entry<String, Integer> entry : counters.entrySet()) {
            int count = entry.getValue();
            double deviation = Math.abs(count - expected) / (double) expected;
            assertTrue(deviation < maxDeviation,
                    String.format("节点 %s 分配 %d 次，期望 %d，偏差 %.1f%% 超过阈值",
                            entry.getKey(), count, expected, deviation * 100));
        }
    }

    // ---------- 辅助方法 ----------

    private ServiceInstance createInstance(String nodeId) {
        long now = System.currentTimeMillis();
        return new ServiceInstance(
                nodeId, "127.0.0.1", 9090, "RTX 5090", 24 * 1024, now, now);
    }
}

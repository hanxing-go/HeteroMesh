package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashLoadBalancerTest {

    private ConsistentHashLoadBalancer lb;

    @BeforeEach
    void setUp() {
        lb = new ConsistentHashLoadBalancer();
    }

    // 空哈希环上 select 返回 null
    @Test
    void shouldReturnNullWhenRingIsEmpty() {
        assertNull(lb.select("any-key"));
        assertEquals(0, lb.size());
    }

    // 单个节点：addNode 后 select 应该命中该节点
    @Test
    void shouldSelectTheOnlyNodeAfterAdd() {
        ServiceInstance node = createInstance("worker-01");
        lb.addNode(node);

        ServiceInstance selected = lb.select("task-001");
        assertNotNull(selected);
        assertEquals("worker-01", selected.getNodeId());
        assertEquals(1, lb.size());
    }

    // 同一个 key 多次 select 始终命中同一个节点（一致性）
    @Test
    void shouldReturnSameNodeForSameKey() {
        ServiceInstance nodeA = createInstance("worker-A");
        ServiceInstance nodeB = createInstance("worker-B");
        lb.addNode(nodeA);
        lb.addNode(nodeB);

        String first = lb.select("stable-key-123").getNodeId();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, lb.select("stable-key-123").getNodeId(),
                    "同一个 key 每次 select 必须返回同一个节点");
        }
    }

    // 不同 key 会分布到不同节点上
    @Test
    void shouldDistributeKeysAcrossNodes() {
        ServiceInstance nodeA = createInstance("worker-A");
        ServiceInstance nodeB = createInstance("worker-B");
        lb.addNode(nodeA);
        lb.addNode(nodeB);

        Set<String> hitNodes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            hitNodes.add(lb.select("task-" + i).getNodeId());
        }

        assertTrue(hitNodes.size() >= 2,
                "1000 个不同 key 应该覆盖到所有节点，实际命中: " + hitNodes.size());
    }

    // removeNode 后该节点不再被 select 命中
    @Test
    void shouldNotReturnRemovedNode() {
        ServiceInstance nodeA = createInstance("worker-A");
        ServiceInstance nodeB = createInstance("worker-B");
        lb.addNode(nodeA);
        lb.addNode(nodeB);

        lb.removeNode("worker-A");
        assertEquals(1, lb.size());

        // 多次 select 都不能命中已移除的节点
        for (int i = 0; i < 500; i++) {
            ServiceInstance selected = lb.select("task-" + i);
            assertNotNull(selected);
            assertNotEquals("worker-A", selected.getNodeId(),
                    "已移除的节点不应该被选中");
        }
    }

    // removeNode 后环上应该清理干净该节点的 150 个虚拟节点
    @Test
    void shouldRemoveAllVirtualNodes() {
        ServiceInstance node = createInstance("worker-01");
        lb.addNode(node);

        // 环上应该有 150 个虚拟节点（即 ring.size() = 150）
        assertEquals(150, lb.getRingSize());

        lb.removeNode("worker-01");

        assertEquals(0, lb.getRingSize());
        assertEquals(0, lb.size());
    }

    // 移除后重新 add 同一个 nodeId，应该正常工作
    @Test
    void shouldWorkAfterRemoveAndReAdd() {
        ServiceInstance node = createInstance("worker-01");
        lb.addNode(node);
        lb.removeNode("worker-01");

        // 重新加入
        ServiceInstance rejoin = createInstance("worker-01");
        lb.addNode(rejoin);

        ServiceInstance selected = lb.select("task-001");
        assertNotNull(selected);
        assertEquals("worker-01", selected.getNodeId());
        assertEquals(1, lb.size());
    }

    // 多个节点时 select 不会返回 null
    @Test
    void shouldAlwaysReturnNodeWhenRingNotEmpty() {
        lb.addNode(createInstance("worker-A"));
        lb.addNode(createInstance("worker-B"));
        lb.addNode(createInstance("worker-C"));

        for (int i = 0; i < 500; i++) {
            assertNotNull(lb.select("key-" + i),
                    "哈希环不为空时 select 永远不该返回 null");
        }
    }

    // 负载分布均匀性：10000 个 key 在 3 个节点间的最大偏差不超过 20%
    @Test
    void shouldDistributeEvenlyAcrossNodes() {
        lb.addNode(createInstance("worker-A"));
        lb.addNode(createInstance("worker-B"));
        lb.addNode(createInstance("worker-C"));

        Map<String, Integer> counters = new HashMap<>();
        int totalKeys = 10000;

        for (int i = 0; i < totalKeys; i++) {
            String nodeId = lb.select("distribution-key-" + i).getNodeId();
            counters.merge(nodeId, 1, Integer::sum);
        }

        // 每个节点应该分到大约 10000/3 ≈ 3333 个 key，允许 ±20% 偏差
        int expected = totalKeys / 3;
        double maxDeviation = 0.20;
        for (Map.Entry<String, Integer> entry : counters.entrySet()) {
            int count = entry.getValue();
            double deviation = Math.abs(count - expected) / (double) expected;
            assertTrue(deviation < maxDeviation,
                    String.format("节点 %s 分配 %d 个 key，期望 %d，偏差 %.1f%% 超过 20%% 阈值",
                            entry.getKey(), count, expected, deviation * 100));
        }
    }

    // size() 正确反映物理节点数（不是虚拟节点数）
    @Test
    void shouldReturnPhysicalNodeCount() {
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

    // 相同 nodeId 重复 addNode 会覆盖旧实例（不会增加 size）
    @Test
    void shouldOverwriteWhenAddingDuplicateNodeId() {
        ServiceInstance oldNode = new ServiceInstance(
                "worker-01", "10.0.0.1", 9090, "RTX 3080", 8 * 1024,
                System.currentTimeMillis(), System.currentTimeMillis());
        ServiceInstance newNode = new ServiceInstance(
                "worker-01", "10.0.0.2", 9090, "RTX 5090", 24 * 1024,
                System.currentTimeMillis(), System.currentTimeMillis());

        lb.addNode(oldNode);
        lb.addNode(newNode);

        assertEquals(1, lb.size());
        // select 返回的应该是新实例（GPU 型号是 RTX 5090）
        ServiceInstance selected = lb.select("any-key");
        assertEquals("RTX 5090", selected.getGpuType());
    }

    // ---------- 辅助方法 ----------

    private ServiceInstance createInstance(String nodeId) {
        long now = System.currentTimeMillis();
        return new ServiceInstance(
                nodeId, "127.0.0.1", 9090,
                "RTX 5090", 24 * 1024,
                now, now);
    }
}

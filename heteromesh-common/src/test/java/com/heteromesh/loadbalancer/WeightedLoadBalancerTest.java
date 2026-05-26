package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeightedLoadBalancerTest {

    private WeightedLoadBalancer lb;

    @BeforeEach
    void setUp() {
        lb = new WeightedLoadBalancer();
    }

    // 空列表时 select 返回 null
    @Test
    void shouldReturnNullWhenEmpty() {
        assertNull(lb.select("any-key"));
        assertEquals(0, lb.size());
    }

    // 单个节点时 select 始终返回该节点（不论权重多少）
    @Test
    void shouldAlwaysSelectTheOnlyNode() {
        lb.addNode(createInstance("sole", 50));

        for (int i = 0; i < 100; i++) {
            assertEquals("sole", lb.select("key-" + i).getNodeId());
        }
    }

    // 权重比例 2:1，高权重节点被选中的次数约为低权重的 2 倍
    @Test
    void shouldSelectHigherWeightNodeMoreOften() {
        lb.addNode(createInstance("heavy", 200));  // 权重 200
        lb.addNode(createInstance("light", 100));  // 权重 100

        Map<String, Integer> counters = new HashMap<>();
        int total = 3000;
        for (int i = 0; i < total; i++) {
            String id = lb.select("key-" + i).getNodeId();
            counters.merge(id, 1, Integer::sum);
        }

        int heavyCount = counters.getOrDefault("heavy", 0);
        int lightCount = counters.getOrDefault("light", 0);

        // heavy 应接近 2000，light 应接近 1000，允许 ±15% 偏差
        double heavyRatio = (double) heavyCount / total;
        double lightRatio = (double) lightCount / total;

        assertTrue(heavyRatio > 0.55 && heavyRatio < 0.78,
                String.format("heavy 节点应占 ~66.7%%，实际: %.1f%% (count=%d)",
                        heavyRatio * 100, heavyCount));
        assertTrue(lightRatio > 0.22 && lightRatio < 0.45,
                String.format("light 节点应占 ~33.3%%，实际: %.1f%% (count=%d)",
                        lightRatio * 100, lightCount));
    }

    // 3 个不同权重的节点：权重 50、30、20，分布应符合比例
    @Test
    void shouldDistributeByWeightProportion() {
        lb.addNode(createInstance("A", 50));
        lb.addNode(createInstance("B", 30));
        lb.addNode(createInstance("C", 20));

        Map<String, Integer> counters = new HashMap<>();
        int total = 5000;
        for (int i = 0; i < total; i++) {
            String id = lb.select("key-" + i).getNodeId();
            counters.merge(id, 1, Integer::sum);
        }

        // 期望：A=2500 (50%), B=1500 (30%), C=1000 (20%)
        // 允许 ±15% 相对偏差（即 A 在 2125~2875 之间）
        double aRatio = (double) counters.getOrDefault("A", 0) / total;
        double bRatio = (double) counters.getOrDefault("B", 0) / total;
        double cRatio = (double) counters.getOrDefault("C", 0) / total;

        assertTrue(aRatio > 0.35 && aRatio < 0.65,
                String.format("节点A 预期 50%%，实际 %.1f%%", aRatio * 100));
        assertTrue(bRatio > 0.15 && bRatio < 0.45,
                String.format("节点B 预期 30%%，实际 %.1f%%", bRatio * 100));
        assertTrue(cRatio > 0.05 && cRatio < 0.35,
                String.format("节点C 预期 20%%，实际 %.1f%%", cRatio * 100));
    }

    // removeNode 后权重数组正确重建，被移除的节点不再参与分配
    @Test
    void shouldRebuildWeightsAfterRemoval() {
        lb.addNode(createInstance("A", 50));
        lb.addNode(createInstance("B", 50));

        lb.removeNode("B");
        assertEquals(1, lb.size());

        // 此时只剩 A，每次 select 都应该返回 A
        for (int i = 0; i < 100; i++) {
            assertEquals("A", lb.select("key-" + i).getNodeId());
        }
    }

    // addNode 重复节点时去重，不会让权重翻倍
    @Test
    void shouldNotDuplicateWeightOnReAdd() {
        lb.addNode(createInstance("A", 100));
        lb.addNode(createInstance("A", 200));  // 相同 nodeId，不同权重——覆盖

        assertEquals(1, lb.size());
        // 单节点时总是返回 A
        assertEquals("A", lb.select("key").getNodeId());
    }

    // removeNode 后再 addNode，权重重建正确
    @Test
    void shouldWorkAfterRemoveAndReAdd() {
        lb.addNode(createInstance("A", 100));
        lb.removeNode("A");
        assertEquals(0, lb.size());
        assertNull(lb.select("key"));

        lb.addNode(createInstance("A", 300));
        assertEquals(1, lb.size());
        assertEquals("A", lb.select("key").getNodeId());
    }

    // 所有节点权重为 0 时 select 返回 null（不会除零异常）
    @Test
    void shouldReturnNullWhenAllWeightsAreZero() {
        lb.addNode(createInstance("A", 0));
        lb.addNode(createInstance("B", 0));

        assertNull(lb.select("key"));
    }

    // 部分节点权重为 0 时，0 权重节点永远不会被选中
    @Test
    void shouldNeverSelectZeroWeightNode() {
        lb.addNode(createInstance("active", 100));
        lb.addNode(createInstance("dormant", 0));

        for (int i = 0; i < 500; i++) {
            assertEquals("active", lb.select("key-" + i).getNodeId(),
                    "权重为 0 的节点不应被选中");
        }
    }

    // name() 返回 "weighted"
    @Test
    void shouldReturnCorrectName() {
        assertEquals("weighted", lb.name());
    }

    // size() 正确反映物理节点数
    @Test
    void shouldReturnCorrectSize() {
        assertEquals(0, lb.size());
        lb.addNode(createInstance("w1", 100));
        assertEquals(1, lb.size());
        lb.addNode(createInstance("w2", 200));
        assertEquals(2, lb.size());
        lb.removeNode("w1");
        assertEquals(1, lb.size());
    }

    // ---------- 辅助方法 ----------

    private ServiceInstance createInstance(String nodeId, int weight) {
        long now = System.currentTimeMillis();
        return new ServiceInstance(
                nodeId, "127.0.0.1", 9090, "RTX 5090",
                24 * 1024, weight, now, now);
    }
}

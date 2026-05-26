package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class WeightedLoadBalancer implements LoadBalancer{

    // 节点列表和累积权重数组，addNode/removeNode 时一起重建
    // 用 synchronized 保护，因为重建不是原子操作
    private final List<ServiceInstance> nodes = new ArrayList<>();
    private final List<Integer> weights = new ArrayList<>();
    private int totalWeight = 0;
    @Override
    public synchronized void addNode(ServiceInstance instance) {
        removeNodeLocked(instance.getNodeId());
        nodes.add(instance);
        rebuildWeights();
        log.info("Weighted: 节点加入, nodeId={}, weight={}, totalWeight={}",
                instance.getNodeId(), instance.getWeight(), totalWeight);
    }

    private void rebuildWeights() {
        weights.clear();
        totalWeight = 0;
        for (ServiceInstance node : nodes) {
            totalWeight += node.getWeight();
            weights.add(totalWeight);
        }
    }

    private void removeNodeLocked(String nodeId) {
        nodes.removeIf(n -> n.getNodeId().equals(nodeId));
    }

    @Override
    public synchronized void removeNode(String nodeId) {
        removeNodeLocked(nodeId);
        rebuildWeights();
        log.info("Weighted: 节点移除, nodeId={}, totalNodes={}", nodeId, nodes.size());
    }

    @Override
    public ServiceInstance select(String key) {
        if (nodes.isEmpty()) {
            return null;
        }
        if (totalWeight <= 0) {
            return null;
        }

        // 生成 [0, totalWeight) 范围的随机数
        int dice = ThreadLocalRandom.current().nextInt(totalWeight);

        // 二分查找：找到第一个累积权重 > dice 的位置
        int lo = 0, hi = weights.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;  // 无符号右移 = 除以 2，防溢出
            if (weights.get(mid) > dice) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return nodes.get(lo);
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public String name() {
        return "weighted";
    }
}

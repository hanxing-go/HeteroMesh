package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RoundRobinLoadBalancer implements LoadBalancer{
    private final List<ServiceInstance> nodes = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public void addNode(ServiceInstance instance) {
        removeNode(instance.getNodeId());
        nodes.add(instance);
        log.info("RoundRobin: 添加节点 nodeId = {}, totalNodes = {}", instance.getNodeId(), nodes.size());
    }

    @Override
    public void removeNode(String nodeId) {
        nodes.removeIf(n -> n.getNodeId().equals(nodeId));
        log.info("RoundRobin: 节点移除， nodeId = {}, totalNodes = {}", nodeId, nodes.size());
    }

    @Override
    public ServiceInstance select(String key) {
        if (nodes.isEmpty()) {
            return null;
        }
        // getAndIncrement() 溢出后会变成负数，& 0x7FFFFFFF 去掉符号位
        // 结果在 [0, 2^31-1] 范围内循环，保证 % nodes.size() 永远非负
        int idx = (counter.getAndIncrement() & 0x7FFFFFFF) % nodes.size();
        return nodes.get(idx);
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public String name() {
        return "RoundRobin";
    }

    @Override
    public ServiceInstance select(String key, Set<String> failedNodes) {
        if (nodes.isEmpty()) {
            return null;
        }
        if (failedNodes == null || failedNodes.isEmpty()) {
            return select(key);
        }

        // 过滤掉失败的节点
        List<ServiceInstance> candidates = new ArrayList<>();
        for (ServiceInstance node : nodes) {
            if (!failedNodes.contains(node.getNodeId())) {
                candidates.add(node);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        int idx = (counter.getAndIncrement() & 0x7FFFFFFF) % candidates.size();
        return candidates.get(idx);
    }

}

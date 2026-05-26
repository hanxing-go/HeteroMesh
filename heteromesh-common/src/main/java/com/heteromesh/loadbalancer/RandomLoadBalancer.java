package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class RandomLoadBalancer implements LoadBalancer{

    // CopyOnWriteArrayList：读多写少场景，读不加锁，写时复制
    private final List<ServiceInstance> nodes = new CopyOnWriteArrayList<>();

    @Override
    public void addNode(ServiceInstance instance) {
        // 避免重复添加同一个节点
        /*
        同一个节点可能因为网络波动断开-重连，Controller 会收到两次 REGISTER。
        如果不先移除旧的，`nodes` 里会出现重复条目，select 时被选中的概率翻倍。
        */
        removeNode(instance.getNodeId());
        nodes.add(instance);
        log.info("Random: 节点加入, nodeId = {}, totalNodes = {}", instance.getNodeId(), nodes.size());
    }

    @Override
    public void removeNode(String nodeId) {
        nodes.removeIf(n -> n.getNodeId().equals(nodeId));
        log.info("Random: 节点移除 nodeId = {}, totalNodes = {}", nodeId, nodes.size());
    }

    /*
    * 适合无状态任务，最简单*/
    @Override
    public ServiceInstance select(String key) {
        if (nodes.isEmpty()) {
            return null;
        }
        int idx = ThreadLocalRandom.current().nextInt(nodes.size());
        return nodes.get(idx);
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public String name() {
        return "random";
    }
}

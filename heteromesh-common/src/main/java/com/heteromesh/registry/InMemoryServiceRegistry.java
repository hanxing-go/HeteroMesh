package com.heteromesh.registry;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class InMemoryServiceRegistry implements ServiceRegistry{
    private final ConcurrentHashMap<String, ServiceInstance> instances = new ConcurrentHashMap<>();
    @Override
    public void register(ServiceInstance instance) {
        instances.put(instance.getNodeId(), instance);
        log.info("注册连接:{}", instance.getNodeId());
    }

    @Override
    public void unregister(String nodeId) {
        instances.remove(nodeId);
        log.info("移除连接: {}",instances.get(nodeId));
    }

    @Override
    public ServiceInstance lookup(String nodeId) {
        return instances.get(nodeId);
    }

    @Override
    public List<ServiceInstance> getAllInstances() {
        return new ArrayList<>(instances.values());
    }

    @Override
    public int size() {
        return instances.size();
    }
}

package com.heteromesh.registry;

import java.util.List;

public interface ServiceRegistry {
    // 注册一个节点
    void register(ServiceInstance instance);

    // 注销一个节点
    void unregister(String nodeId);

    // 查询一个节点
    ServiceInstance lookup(String nodeId);

    // 获取所有在线节点
    List<ServiceInstance> getAllInstances();

    // 获取在线节点数量
    int size();
}

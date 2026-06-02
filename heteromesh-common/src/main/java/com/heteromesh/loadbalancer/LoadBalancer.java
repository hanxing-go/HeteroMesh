package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.spi.SPI;

import java.util.Set;

/*
* 负载均衡器接口
* 内部维护哈希环，节点上线时调用addNode/removeNode
* */
@SPI("consistentHash")
public interface LoadBalancer {
    /*
    * 节点上线时调用：把节点加入哈希环
    * */
    void addNode(ServiceInstance instance);

    /*
    * 节点下线时调用：从哈希环移除节点中的所有虚拟节点
    * */
    void removeNode(String nodeId);

    /*
    * 根据key(如requestId) 选择一个节点
    * @return 选中的ServiceInstance, 没有可用节点时返回null*/
    ServiceInstance select(String key);

    /*
    * 当前哈希环上有多少个物理节点*/
    int size();

    /*
    * 返回负载均衡策略名称*/
    String name();

    ServiceInstance select(String key, Set<String> failedNodes);
}

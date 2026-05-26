package com.heteromesh.controller.node;

import com.heteromesh.loadbalancer.LoadBalancer;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定期扫描注册中心，踢出心跳超时的节点
 */
@Slf4j
public class DeadNodeDetector {
    private final ServiceRegistry registry;
    private final NodeChannelMap nodeChannelMap;
    private final long timeoutMs;
    private final LoadBalancer loadBalancer;



    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r ->{
                Thread t = new Thread(r, "dead-node-dector");
                t.setDaemon(true);
                return t;
            });

    public DeadNodeDetector(ServiceRegistry registry, NodeChannelMap nodeChannelMap, LoadBalancer lb, long timeoutMs) {
        this.registry = registry;
        this.nodeChannelMap = nodeChannelMap;
        this.timeoutMs = timeoutMs;
        this.loadBalancer = lb;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::scan, 5, 5, TimeUnit.SECONDS);
        log.info("DeadNodeDetector 启动, timeout={}ms", timeoutMs);
    }

    void scan() {
        List<ServiceInstance> instances = registry.getAllInstances();
        long now = System.currentTimeMillis();

        for (ServiceInstance instance : instances) {
            if (!instance.isAlive((timeoutMs))) {
                log.warn("节点心跳超时，删除节点: nodeId = {}", instance.getNodeId());
                // 移除
                registry.unregister(instance.getNodeId());
                loadBalancer.removeNode(instance.getNodeId());  // 哈希环也要移除
                // 关闭channel并且解绑
                Channel channel = nodeChannelMap.getChannel(instance.getNodeId());
                if (channel != null && channel.isActive()) {
                    channel.close();
                }
                nodeChannelMap.unbind(channel);
            }
        }
    }

    public void stop() {
        scheduler.shutdown();
    }
}

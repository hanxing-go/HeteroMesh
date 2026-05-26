package com.heteromesh.registry;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ServiceInstance {
    private String nodeId;       // 节点唯一标识，如 "worker-gpu-01"
    private String host;         // Worker 的 IP 地址
    private int port;            // Worker 的端口
    //gpu状态
    private String gpuType;
    private int vramFree;

    // 权重
    private int weight = 100; // 默认100，用于加权负载均衡

    private long registerTime;   // 注册时间戳
    private long lastHeartbeat;  // 最后一次心跳时间戳

    // 向后兼容：不带 weight 的构造函数，默认 weight=100
    public ServiceInstance(String nodeId, String host, int port, String gpuType,
                           int vramFree, long registerTime, long lastHeartbeat) {
        this(nodeId, host, port, gpuType, vramFree, 100, registerTime, lastHeartbeat);
    }

    // Worker 判活逻辑
    public boolean isAlive(long timeoutMs) {
        return System.currentTimeMillis() - lastHeartbeat < timeoutMs;
    }
}

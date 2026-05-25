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

    private long registerTime;   // 注册时间戳
    private long lastHeartbeat;  // 最后一次心跳时间戳

    // 构造方法 + getter/setter ...

    // Worker 判活逻辑
    public boolean isAlive(long timeoutMs) {
        return System.currentTimeMillis() - lastHeartbeat < timeoutMs;
    }
}

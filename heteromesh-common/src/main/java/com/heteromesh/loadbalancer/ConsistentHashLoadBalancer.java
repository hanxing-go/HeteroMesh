package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ConsistentHashLoadBalancer implements LoadBalancer{
    // 每个物理节点对应的虚拟节点数
    private static final int VIRTUAL_NODES = 150;
    // 哈希环: hash值——> nodeId
    // TreeMap 保证 key 有序，支持 ceilingEntry（找 ≥ 给定值的最小 key）
    private final TreeMap<Integer, String> ring = new TreeMap<>();

    // nodeId -> ServiceInstance(select 时根据 nodeId拿到完整信息)
    private final Map<String, ServiceInstance> nodes = new ConcurrentHashMap<>();

    // ---------- 哈希函数 ----------

    /**
     * 用 MD5 对 key 取哈希，取低 32 位转为 int 正数
     * 为什么用 MD5 而不是 key.hashCode()？
     * - String.hashCode() 分布不够均匀，某些 pattern 会导致热点
     * - MD5 是密码学哈希，任意输入的分布都非常均匀
     */
    private int hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            // 取前 4 个字节，拼成 int，& 0x7FFFFFFF 确保是正数
            int h = ((digest[3] & 0xFF) << 24)
                    | ((digest[2] & 0xFF) << 16)
                    | ((digest[1] & 0xFF) << 8)
                    | ((digest[0] & 0xFF));
            return h & 0x7FFFFFFF;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    // ----------------------节点管理--------------------------
    @Override
    public void addNode(ServiceInstance instance) {
        String nodeId = instance.getNodeId();

        // 存储物理节点信息
        nodes.put(nodeId, instance);
        // 环上插入150个虚拟节点
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            String virtualKey = nodeId + "#" + i;
            int h = hash(virtualKey);
            ring.put(h, nodeId);
        }

        log.info("节点加入哈希环: nodeId = {}, virtualNodes = {}, ringSize = {}", nodeId, VIRTUAL_NODES, ring.size());
    }

    @Override
    public void removeNode(String nodeId) {
        // 删除物理节点
        nodes.remove(nodeId);

        // 删除环上所有的虚拟节点
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            String virtualKey = nodeId + "#" + i;
            int h = hash(virtualKey);
            ring.remove(h);
        }

        log.info("节点从哈希环被移除: nodeId = {}", nodeId);
    }

    @Override
    public ServiceInstance select(String key) {
        if (nodes.isEmpty()) {
            return null;
        }

        int h = hash(key);
        // 顺时针找第一个 >= h的虚拟节点
        Map.Entry<Integer, String> entry = ring.ceilingEntry(h);
        if (entry == null) {
            // 超过环上最大值 -> 回到环节点（第一个节点）
            entry = ring.firstEntry();
        }

        String nodeId = entry.getValue();
        return nodes.get(nodeId);
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public String name() {
        return "consistentHash";
    }

    // 返回哈希环上的虚拟节点总数（供测试验证虚拟节点是否正确清理）
    public int getRingSize() {
        return ring.size();
    }
}

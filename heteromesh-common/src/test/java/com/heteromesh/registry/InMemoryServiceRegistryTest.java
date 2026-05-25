package com.heteromesh.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryServiceRegistryTest {

    private InMemoryServiceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryServiceRegistry();
    }

    // 注册后可以通过 nodeId 查到完整信息
    @Test
    void shouldRegisterAndLookup() {
        ServiceInstance instance = new ServiceInstance(
                "worker-gpu-01", "192.168.1.10", 9090, "RTX 5090", 24 * 1024,
                System.currentTimeMillis(), System.currentTimeMillis());

        registry.register(instance);
        ServiceInstance found = registry.lookup("worker-gpu-01");

        assertNotNull(found);
        assertEquals("192.168.1.10", found.getHost());
        assertEquals(9090, found.getPort());
        assertEquals("RTX 5090", found.getGpuType());
        assertEquals(24 * 1024, found.getVramFree());
    }

    // 查询不存在的 nodeId 返回 null
    @Test
    void shouldReturnNullForUnknownNode() {
        ServiceInstance found = registry.lookup("no-such-node");
        assertNull(found);
    }

    // 注销后查不到，计数器减 1
    @Test
    void shouldUnregister() {
        ServiceInstance instance = new ServiceInstance(
                "worker-01", "192.168.1.10", 9090, "CPU", 0,
                System.currentTimeMillis(), System.currentTimeMillis());

        registry.register(instance);
        assertEquals(1, registry.size());

        registry.unregister("worker-01");
        assertEquals(0, registry.size());
        assertNull(registry.lookup("worker-01"));
    }

    // getAllInstances 返回所有已注册节点
    @Test
    void shouldGetAllInstances() {
        registry.register(new ServiceInstance("w1", "10.0.0.1", 9090, "CPU", 0, System.currentTimeMillis(), System.currentTimeMillis()));
        registry.register(new ServiceInstance("w2", "10.0.0.2", 9090, "CPU", 0, System.currentTimeMillis(), System.currentTimeMillis()));
        registry.register(new ServiceInstance("w3", "10.0.0.3", 9090, "CPU", 0, System.currentTimeMillis(), System.currentTimeMillis()));

        List<ServiceInstance> all = registry.getAllInstances();

        assertEquals(3, all.size());
    }

    // size() 随注册/注销正确增减
    @Test
    void shouldReturnCorrectSize() {
        assertEquals(0, registry.size());

        registry.register(new ServiceInstance("w1", "10.0.0.1", 9090, "CPU", 0, System.currentTimeMillis(), System.currentTimeMillis()));
        assertEquals(1, registry.size());

        registry.register(new ServiceInstance("w2", "10.0.0.2", 9090, "CPU", 0, System.currentTimeMillis(), System.currentTimeMillis()));
        assertEquals(2, registry.size());
    }

    // 重复注册同一 nodeId 会覆盖旧数据，数量不变
    @Test
    void shouldOverwriteWhenRegisteringDuplicate() {
        ServiceInstance old = new ServiceInstance("w1", "10.0.0.1", 9090, "CPU", 0, 1000L, 1000L);
        ServiceInstance updated = new ServiceInstance("w1", "10.0.0.1", 9091, "CPU", 0, 2000L, 2000L);

        registry.register(old);
        registry.register(updated);

        ServiceInstance found = registry.lookup("w1");
        assertEquals(9091, found.getPort());
        assertEquals(2000L, found.getRegisterTime());
        assertEquals(1, registry.size());
    }

    // isAlive：心跳在超时内返回 true，超过返回 false
    @Test
    void shouldCheckIsAlive() {
        ServiceInstance alive = new ServiceInstance(
                "w1", "10.0.0.1", 9090, "CPU", 0,
                System.currentTimeMillis(),
                System.currentTimeMillis());

        assertTrue(alive.isAlive(5000));

        ServiceInstance dead = new ServiceInstance(
                "w2", "10.0.0.2", 9090, "CPU", 0,
                System.currentTimeMillis(),
                System.currentTimeMillis() - 10000);

        assertFalse(dead.isAlive(5000));
    }
}

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

    @Test
    void shouldRegisterAndLookup() {
        ServiceInstance instance = new ServiceInstance(
                "worker-gpu-01", "192.168.1.10", 9090,
                System.currentTimeMillis(), System.currentTimeMillis());

        registry.register(instance);
        ServiceInstance found = registry.lookup("worker-gpu-01");

        assertNotNull(found);
        assertEquals("192.168.1.10", found.getHost());
        assertEquals(9090, found.getPort());
    }

    @Test
    void shouldReturnNullForUnknownNode() {
        ServiceInstance found = registry.lookup("no-such-node");
        assertNull(found);
    }

    @Test
    void shouldUnregister() {
        ServiceInstance instance = new ServiceInstance(
                "worker-01", "192.168.1.10", 9090,
                System.currentTimeMillis(), System.currentTimeMillis());

        registry.register(instance);
        assertEquals(1, registry.size());

        registry.unregister("worker-01");
        assertEquals(0, registry.size());
        assertNull(registry.lookup("worker-01"));
    }

    @Test
    void shouldGetAllInstances() {
        registry.register(new ServiceInstance("w1", "10.0.0.1", 9090, System.currentTimeMillis(), System.currentTimeMillis()));
        registry.register(new ServiceInstance("w2", "10.0.0.2", 9090, System.currentTimeMillis(), System.currentTimeMillis()));
        registry.register(new ServiceInstance("w3", "10.0.0.3", 9090, System.currentTimeMillis(), System.currentTimeMillis()));

        List<ServiceInstance> all = registry.getAllInstances();

        assertEquals(3, all.size());
    }

    @Test
    void shouldReturnCorrectSize() {
        assertEquals(0, registry.size());

        registry.register(new ServiceInstance("w1", "10.0.0.1", 9090, System.currentTimeMillis(), System.currentTimeMillis()));
        assertEquals(1, registry.size());

        registry.register(new ServiceInstance("w2", "10.0.0.2", 9090, System.currentTimeMillis(), System.currentTimeMillis()));
        assertEquals(2, registry.size());
    }

    @Test
    void shouldOverwriteWhenRegisteringDuplicate() {
        ServiceInstance old = new ServiceInstance("w1", "10.0.0.1", 9090, 1000L, 1000L);
        ServiceInstance updated = new ServiceInstance("w1", "10.0.0.1", 9091, 2000L, 2000L);

        registry.register(old);
        registry.register(updated);

        ServiceInstance found = registry.lookup("w1");
        assertEquals(9091, found.getPort());
        assertEquals(2000L, found.getRegisterTime());
        assertEquals(1, registry.size());
    }

    @Test
    void shouldCheckIsAlive() {
        ServiceInstance alive = new ServiceInstance(
                "w1", "10.0.0.1", 9090,
                System.currentTimeMillis(),
                System.currentTimeMillis()); // 刚刚有心跳

        assertTrue(alive.isAlive(5000)); // 5 秒超时，刚有心跳 → 活

        ServiceInstance dead = new ServiceInstance(
                "w2", "10.0.0.2", 9090,
                System.currentTimeMillis(),
                System.currentTimeMillis() - 10000); // 10 秒前有心跳

        assertFalse(dead.isAlive(5000)); // 5 秒超时，10 秒前有心跳 → 死
    }
}

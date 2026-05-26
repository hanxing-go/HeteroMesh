package com.heteromesh.loadbalancer;

import com.heteromesh.spi.SpiExtensionLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoadBalancerFactoryTest {

    // getLoadBalancer("consistentHash") 返回 ConsistentHashLoadBalancer 实例
    @Test
    void shouldLoadConsistentHashByName() {
        LoadBalancer lb = LoadBalancerFactory.getLoadBalancer("consistentHash");
        assertNotNull(lb);
        assertInstanceOf(ConsistentHashLoadBalancer.class, lb);
        assertEquals("consistentHash", lb.name());
    }

    // getLoadBalancer("random") 返回 RandomLoadBalancer 实例
    @Test
    void shouldLoadRandomByName() {
        LoadBalancer lb = LoadBalancerFactory.getLoadBalancer("random");
        assertNotNull(lb);
        assertInstanceOf(RandomLoadBalancer.class, lb);
        assertEquals("random", lb.name());
    }

    // getLoadBalancer("roundRobin") 返回 RoundRobinLoadBalancer 实例
    @Test
    void shouldLoadRoundRobinByName() {
        LoadBalancer lb = LoadBalancerFactory.getLoadBalancer("roundRobin");
        assertNotNull(lb);
        assertInstanceOf(RoundRobinLoadBalancer.class, lb);
        assertEquals("RoundRobin", lb.name());
    }

    // getLoadBalancer("weighted") 返回 WeightedLoadBalancer 实例
    @Test
    void shouldLoadWeightedByName() {
        LoadBalancer lb = LoadBalancerFactory.getLoadBalancer("weighted");
        assertNotNull(lb);
        assertInstanceOf(WeightedLoadBalancer.class, lb);
        assertEquals("weighted", lb.name());
    }

    // getDefault() 返回默认实现 ConsistentHashLoadBalancer（由 @SPI("consistentHash") 指定）
    @Test
    void shouldReturnConsistentHashAsDefault() {
        LoadBalancer lb = LoadBalancerFactory.getDefault();
        assertNotNull(lb);
        assertInstanceOf(ConsistentHashLoadBalancer.class, lb,
                "默认实现应该是一致性哈希（由 @SPI 注解指定）");
        assertEquals("consistentHash", lb.name());
    }

    // 传入不存在的名称时抛出 IllegalArgumentException
    @Test
    void shouldThrowForUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> {
            LoadBalancerFactory.getLoadBalancer("nonexistent_strategy");
        });
    }

    // 传入 null 时抛出 IllegalArgumentException
    @Test
    void shouldThrowForNullName() {
        assertThrows(IllegalArgumentException.class, () -> {
            LoadBalancerFactory.getLoadBalancer(null);
        });
    }

    // 传入空字符串时抛出 IllegalArgumentException
    @Test
    void shouldThrowForEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> {
            LoadBalancerFactory.getLoadBalancer("");
        });
    }

    // 连续两次 getLoadBalancer 用同一个名字返回同一个实例（单例缓存）
    @Test
    void shouldReturnSameInstanceForSameName() {
        LoadBalancer lb1 = LoadBalancerFactory.getLoadBalancer("random");
        LoadBalancer lb2 = LoadBalancerFactory.getLoadBalancer("random");
        assertSame(lb1, lb2, "同一个名称两次调用应返回同一个实例（SPI 缓存）");
    }

    // 不同名字返回不同实例
    @Test
    void shouldReturnDifferentInstancesForDifferentNames() {
        LoadBalancer lb1 = LoadBalancerFactory.getLoadBalancer("random");
        LoadBalancer lb2 = LoadBalancerFactory.getLoadBalancer("roundRobin");
        assertNotSame(lb1, lb2);
    }
}

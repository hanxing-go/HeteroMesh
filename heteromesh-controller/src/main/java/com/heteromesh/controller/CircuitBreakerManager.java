package com.heteromesh.controller;

import com.heteromesh.rpc.CircuitBreaker;
import com.heteromesh.rpc.CircuitBreakerConfig;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 管理所有 Worker 节点的熔断器。
 * 每个 Worker 一个独立的 CircuitBreaker 实例。
 */
public class CircuitBreakerManager {
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig config;

    public CircuitBreakerManager(CircuitBreakerConfig config) {
        this.config = config;
    }

    /** 获取或创建某个节点的熔断器 */
    private CircuitBreaker getOrCreate(String nodeId) {
//        return breakers.computeIfAbsent(nodeId, id -> new CircuitBreaker(id, config));
        //computeIfAbsent等价于有直接返回，没有则创建一个
        CircuitBreaker breaker = breakers.get(nodeId);
        if (breaker == null) {
            breaker = new CircuitBreaker(nodeId, config);
            breakers.put(nodeId, breaker);
        }
        return breaker;
    }

    /*记录成功*/
    public void onSuccess(String nodeId) {
        getOrCreate(nodeId).onSuccess();
    }

    /*记录失败*/
    public void onFailure(String nodeId) {
        getOrCreate(nodeId).onFailure();
    }

    /*返回当前处于OPEN状态的节点（这些节点应该被排除）*/
    public Set<String> getOpenNodes() {
        return breakers.entrySet().stream()
                .filter(e -> e.getValue().isOpen())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        /*等价于下面的写法*/
//        Set<String> result = new HashSet<>();
//        for (Map.Entry<String, CircuitBreaker> e : breakers.entrySet()) {
//            if (e.getValue().isOpen()) {
//                result.add(e.getKey());
//            }
//        }
//        return result;
    }

    /*移除某一个节点*/
    public void remove(String nodeId) {
        breakers.remove(nodeId);
    }

    public CircuitBreaker getCircuitBreaker(String nodeId) {
        return breakers.get(nodeId);
    }

}

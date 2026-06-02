package com.heteromesh.rpc;

import lombok.Data;

/*
* 熔断器配置
* */
@Data
public class CircuitBreakerConfig {

    public static final int DEFAULT_FAILURE_THRESHOLD = 5;
    public static final long DEFAULT_OPEN_DURATION_MS = 30_000; //30s冷却
    public static final int DEFAULT_HALF_OPEN_SUCCESS = 2;

    private final int failureThreshold;
    private final long openDurationMs;
    private final int halfOpenSuccessThreshold;


    public CircuitBreakerConfig(int faliureThreshold, long openDurationMs, int halfOPenSuccessThreshold) {
        this.failureThreshold = faliureThreshold;
        this.openDurationMs = openDurationMs;
        this.halfOpenSuccessThreshold = halfOPenSuccessThreshold;
    }

    public CircuitBreakerConfig() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_DURATION_MS, DEFAULT_HALF_OPEN_SUCCESS);
    }
}

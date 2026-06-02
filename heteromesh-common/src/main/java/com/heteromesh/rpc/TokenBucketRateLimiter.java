package com.heteromesh.rpc;

/**
 * 令牌桶限流器。参考 Guava RateLimiter 惰性填充设计。
 *
 * 每次 tryAcquire() 时计算时间差来补充令牌，不用后台线程。
 */
public class TokenBucketRateLimiter {
    private final long capacity;                // 桶容量
    private final double refillRatePerMs;       // 每毫秒生成多少令牌

    private double availableTokens;             // 当前可用令牌数
    private long lastRefillTime;                // 上次填充时间

    public TokenBucketRateLimiter(long permitsPerSecond) {
        this.capacity = permitsPerSecond;
        this.refillRatePerMs = (double) permitsPerSecond / 1000;
        this.availableTokens = capacity;
        this.lastRefillTime = System.currentTimeMillis();
    }

    /* 尝试获取一个令牌，拿到则返回true，否则返回false*/
    public synchronized boolean tryAcquire() {
        // 先补充令牌
        refill();
        if (availableTokens < 1) {
            return false;
        }
        availableTokens--;
        return true;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;        // 计算距离上次填充过了多长时间

        availableTokens = Math.min(capacity, availableTokens + elapsed * refillRatePerMs);  // 更新当前桶的令牌数
        lastRefillTime = now;
    }
}

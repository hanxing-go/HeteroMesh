package com.heteromesh.rpc;


/**
 * 滑动窗口限流器。参考 Sentinel LeapArray 设计。
 * 用环形数组避免频繁创建/销毁格子对象
 *
 * 把时间窗口切成 N 个格子，统计最近 intervalMs 内的请求数。
 */
public class SlidingWindowRateLimiter {
    private final int windowCount;          // 格子数量
    private final long windowLengthMs;      // 每个格子的时长
    private final long intervalMs;          // 总窗口时长  = windowCount * windowLengthMs
    private final long[] counters;          // 环形数组
    private final long[] timestamps;        // 每个格子对应的时间戳
    private final long maxRequests;         // 阈值


    /**
     * @param maxRequests      阈值
     * @param windowCount      格子数
     * @param intervalMs       总窗口时长（毫秒）
     */
    public SlidingWindowRateLimiter(long maxRequests, int windowCount, long intervalMs) {
        this.maxRequests = maxRequests;
        this.windowCount = windowCount;
        this.intervalMs = intervalMs;
        this.windowLengthMs = intervalMs / windowCount;
        this.counters = new long[windowCount];
        this.timestamps = new long[windowCount];
    }

    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long currentWindowStart = now - (now % windowLengthMs); // 当前格子的起始时间

        int total = 0;
        for (int i = 0; i < windowCount; i++) {
            // 过期格子重置
            if (now - timestamps[i] > intervalMs) {
                counters[i] = 0;
                timestamps[i] = currentWindowStart;
            }
            total += counters[i];
        }

        if (total >= maxRequests) return false;

        // 找到当前格子的位置并 +1
        int idx = (int) ((currentWindowStart / windowLengthMs) % windowCount);
        if (timestamps[idx] != currentWindowStart) {
            timestamps[idx] = currentWindowStart;
            counters[idx] = 0;
        }
        counters[idx]++;
        return true;
    }


}

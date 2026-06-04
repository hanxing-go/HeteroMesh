package com.heteromesh.rpc;


/**
 * 单个节点的熔断器。参考 Sentinel 三态状态机设计。
 *
 * 状态流转：
 *   CLOSED ──failureCount>=threshold──→ OPEN
 *   OPEN ──openDurationMs 过后───────→ HALF_OPEN
 *   HALF_OPEN ──探测成功─────────────→ CLOSED
 *   HALF_OPEN ──探测失败─────────────→ OPEN
 */
public class CircuitBreaker {
    enum State {CLOSED, OPEN, HALF_OPEN}

    private final String nodeId;
    private final CircuitBreakerConfig config;
    private State state = State.CLOSED;
    private int failureCount = 0;
    private long openedAt = 0;
    private int successCount = 0;

    public CircuitBreaker(String nodeId, CircuitBreakerConfig config) {
        this.nodeId = nodeId;
        this.config = config;
    }

    /** 检查请求是否被允许通过 */
    public boolean allowRequest() {
        switch (state) {
            case CLOSED: return true;
            case OPEN: if (System.currentTimeMillis() - openedAt >= config.getOpenDurationMs()) {
                    transitionTo(State.HALF_OPEN);
                    return true;
                }
                return false;
            case HALF_OPEN: return true;
            default: return false;
        }
    }
    /** 请求成功 */
    public void onSuccess() {
        switch (state) {
            case CLOSED: failureCount = 0; break;
            case HALF_OPEN:
                if (++successCount >= config.getHalfOpenSuccessThreshold()) {
                    transitionTo(State.CLOSED);
                }
                break;
            case OPEN: break;
        }
    }

    /* 请求失败*/
    public void onFailure() {
        switch (state) {
            case CLOSED:
                /* 如果当前为闭合状态，并且超过熔断阈值，则熔断*/
                if (++failureCount >= config.getFailureThreshold()) {
                    transitionTo(State.OPEN);
                }
                break;
            case HALF_OPEN:
                /* 如果是半熔断状态，立刻进行熔断*/
                transitionTo(State.OPEN);
                break;
            case OPEN:
                /* 如果已经熔断，则重置时间*/
                openedAt = System.currentTimeMillis(); break;
        }
    }

    private void transitionTo(State newState) {
        this.state = newState;
        if (newState == State.OPEN) {
            this.openedAt = System.currentTimeMillis();
        } else if (newState == State.CLOSED) {
            this.failureCount = 0;
            this.successCount = 0;
        } else if (newState == State.HALF_OPEN) {
            this.successCount = 0;
        }
    }


    public boolean isOpen() {
        return state == State.OPEN;
    }
    public State getState() {
        return this.state;
    }

    public String getNodeId() {
        return this.nodeId;
    }
}

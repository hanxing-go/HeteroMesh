package com.heteromesh.task;



/**
 * 任务状态。
 *
 * 注意：状态不是随便跳的，必须按 TaskStateMachine 允许的路径流转。
 */
public enum TaskStatus {
    SUBMITTED,      // 已提交到Controller，但是还没有选择Worker
    DISPATCHING,    // Controller 正在选择Worker / 正在转发
    RUNNING,        // Worker 成功接收并且已经开始执行
    SUCCEEDED,      // 执行成功
    FAILED,         // 执行失败，并且不再重试
    TIMEOUT,        // 超时
    CANCELED;       // 用户取消任务

    public boolean isTerminal() {
        // TODO:
        // SUCCEEDED / FAILED / TIMEOUT / CANCELED 返回 true
        // 其他状态返回 false
        if (this == SUCCEEDED || this == FAILED || this == TIMEOUT || this == CANCELED) {
            return true;
        }
        return false;
    }
}

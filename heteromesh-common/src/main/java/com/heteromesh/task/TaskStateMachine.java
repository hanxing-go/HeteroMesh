package com.heteromesh.task;


import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态机。
 *
 * 职责：
 * 1. 定义哪些状态可以流转到哪些状态
 * 2. 拒绝非法状态跳转
 */
public class TaskStateMachine {
    // Map映射表，记录哪两个状态之间可以单向状态：比如    状态A -> 可以到状态B，C
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(TaskStatus.class);

    static {
        // 加入状态单向通道的映射
        ALLOWED_TRANSITIONS.put(TaskStatus.SUBMITTED, EnumSet.of(TaskStatus.DISPATCHING, TaskStatus.CANCELED));

        ALLOWED_TRANSITIONS.put(TaskStatus.DISPATCHING, EnumSet.of(TaskStatus.RUNNING, TaskStatus.FAILED,
                TaskStatus.TIMEOUT, TaskStatus.CANCELED));

        ALLOWED_TRANSITIONS.put(TaskStatus.RUNNING,
                EnumSet.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.TIMEOUT, TaskStatus.CANCELED));

        ALLOWED_TRANSITIONS.put(TaskStatus.SUCCEEDED, EnumSet.noneOf(TaskStatus.class));

        ALLOWED_TRANSITIONS.put(TaskStatus.FAILED, EnumSet.noneOf(TaskStatus.class));

        ALLOWED_TRANSITIONS.put(TaskStatus.TIMEOUT, EnumSet.noneOf(TaskStatus.class));

        ALLOWED_TRANSITIONS.put(TaskStatus.CANCELED, EnumSet.noneOf(TaskStatus.class));
    }

    public boolean canTransit(TaskStatus from, TaskStatus to) {
        // TODO: 从 ALLOWED_TRANSITIONS 里取出 from 对应的集合，判断是否包含 to
        Set<TaskStatus> allowedTransitions = ALLOWED_TRANSITIONS.get(from);
        if (allowedTransitions.contains(to)) {
            return true;
        }
        return false;
    }

    public void transit(TaskMetadata task, TaskStatus to) {
        // 1. 读取 task 当前状态 from
        TaskStatus from = task.getStatus();
        // 2. 如果不能从 from 转到 to，抛 IllegalStateException
        if (!canTransit(from, to)) {
            throw new IllegalStateException("state:" + from + "can not transit to state" + to);
        }
        // 3. 设置 task.status = to
        task.setStatus(to);
        // 4. 调用 task.markUpdated()
        task.markUpdated();
    }
}

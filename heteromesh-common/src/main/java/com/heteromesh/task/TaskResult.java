package com.heteromesh.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Worker 执行任务后的结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskResult {
    private String taskId;              // 标记任务Id
    private TaskStatus status;          // 标记成功或者失败状态
    private String output;              // 成功时的输出
    private String errorMessage;        // 失败时的输出
    private long startedAt;             // 用来统计耗时
    private long finishedAt;            // 用来统计耗时
    private String workerId;            // 追踪任务在哪个Worker上执行
}

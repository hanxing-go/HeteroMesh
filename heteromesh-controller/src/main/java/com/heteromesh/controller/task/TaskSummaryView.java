package com.heteromesh.controller.task;

import com.heteromesh.task.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskSummaryView {
    private String taskId;
    private String taskType;
    private TaskStatus status;
    private String assignedWorkerId;
    private int retryCount;
    private long createdAt;
    private long updatedAt;
    private boolean hasResult;
}

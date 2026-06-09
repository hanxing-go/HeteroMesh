package com.heteromesh.controller.task;

import com.heteromesh.task.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDetailView {
    private String taskId;
    private String taskType;
    private TaskStatus status;
    private String assignedWorkerId;
    private int retryCount;
    private long createdAt;
    private long updatedAt;
    private boolean hasResult;

    private String payload;
    private Map<String, String> resourceRequirements;
    private List<String> attemptedWorkers;
    private String output;
    private String errorMessage;
    private long startedAt;
    private long finishedAt;
    private long durationMs;

}

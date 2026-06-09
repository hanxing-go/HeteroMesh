package com.heteromesh.controller.task;

import com.heteromesh.task.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TaskQueryService {
    private final TaskStore taskStore;

    public TaskQueryService(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    public TaskDetailView getTask(String taskId) {
        TaskMetadata metadata = taskStore.get(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return toDetailView(metadata);
    }

    private TaskDetailView toDetailView(TaskMetadata metadata) {
        TaskRequest request = metadata.getRequest();
        TaskResult result = metadata.getResult();

        boolean hasResult = result != null;

        String output = hasResult ? result.getOutput() : null;
        String errorMessage = hasResult ? result.getErrorMessage() : null;
        long startedAt = hasResult ? result.getStartedAt() : 0;
        long finishedAt = hasResult ? result.getFinishedAt() : 0;

        long durationMs = 0;
        if (hasResult && finishedAt > startedAt) {
            durationMs = finishedAt - startedAt;
        }

        Map<String, String> resourceRequirements = request.getResourceRequirements() == null
                ? new HashMap<>()
                : new HashMap<>(request.getResourceRequirements());

        List<String> attemptedWorkers = new ArrayList<>(metadata.getAttemptedWorkers());

        return new TaskDetailView(
                metadata.getTaskId(),
                request.getTaskType(),
                metadata.getStatus(),
                metadata.getAssignedWorkerId(),
                metadata.getRetryCount(),
                metadata.getCreatedAt(),
                metadata.getUpdatedAt(),
                hasResult,
                request.getPayload(),
                resourceRequirements,
                attemptedWorkers,
                output,
                errorMessage,
                startedAt,
                finishedAt,
                durationMs
        );
    }

    private TaskSummaryView toSummaryView(TaskMetadata metadata) {
        TaskRequest request = metadata.getRequest();

        return new TaskSummaryView(
                metadata.getTaskId(),
                request.getTaskType(),
                metadata.getStatus(),
                metadata.getAssignedWorkerId(),
                metadata.getRetryCount(),
                metadata.getCreatedAt(),
                metadata.getUpdatedAt(),
                metadata.getResult() != null
        );
    }

    public List<TaskSummaryView> listAll() {
        return taskStore.listAll().stream()
                .map(this::toSummaryView)
                .toList();
    }

    public List<TaskSummaryView> listByStatus(TaskStatus status) {
        return taskStore.listByStatus(status).stream()
                .map(this::toSummaryView)
                .toList();
    }

    public Map<TaskStatus, Long> countByStatus() {
        Map<TaskStatus, Long> result = new EnumMap<>(TaskStatus.class);

        for (TaskStatus status : TaskStatus.values()) {
            result.put(status, 0L);
        }

        for (TaskMetadata metadata : taskStore.listAll()) {
            TaskStatus status = metadata.getStatus();
            result.put(status, result.get(status) + 1);
        }

        return result;
    }
}

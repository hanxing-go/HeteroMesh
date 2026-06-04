package com.heteromesh.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTaskStore implements TaskStore{
    private final ConcurrentHashMap<String, TaskMetadata> tasks = new ConcurrentHashMap<>();
    private final TaskStateMachine stateMachine = new TaskStateMachine();


    @Override
    public TaskMetadata create(TaskRequest request) {
        // 客户端没传 taskId 时，Controller 生成
        String taskId = request.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
            request.setTaskId(taskId);
        }

        TaskMetadata metadata = new TaskMetadata(taskId, request);
        // 防止客户端重复提交任务
        TaskMetadata existing = tasks.putIfAbsent(taskId, metadata);

        if (existing != null) {
            throw new IllegalArgumentException("Task already exists" + taskId);
        }

        return metadata;
    }

    @Override
    public Optional<TaskMetadata> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public void updateStatus(String taskId, TaskStatus status) {
        TaskMetadata task = requireTask(taskId);
        stateMachine.transit(task, status);
    }

    @Override
    public void assignWorker(String taskId, String workerId) {
        TaskMetadata task = requireTask(taskId);
        task.setAssignedWorkerId(workerId);
        task.markUpdated();
    }

    @Override
    public void recordAttempt(String taskId, String workerId) {
        TaskMetadata task = requireTask(taskId);
        // 记录已经分配过的工作节点
        task.getAttemptedWorkers().add(workerId);
        task.setRetryCount(task.getRetryCount() + 1);
        task.markUpdated();
    }

    @Override
    public void complete(String taskId, TaskResult result) {
        TaskMetadata task = requireTask(taskId);
        stateMachine.transit(task, result.getStatus());
        task.setResult(result);
        task.markUpdated();
    }

    @Override
    public List<TaskMetadata> listAll() {

        return new ArrayList<>(tasks.values());
    }

    @Override
    public List<TaskMetadata> listByStatus(TaskStatus status) {
        return tasks.values().stream()
                .filter(task -> task.getStatus() == status)
                .toList();
    }


    private TaskMetadata requireTask(String taskId) {
        TaskMetadata task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return task;
    }
}

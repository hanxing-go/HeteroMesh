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

        //1: 确认 task 还不是终态
        ensureNotTerminal(tasks.get(taskId));
        //2: 确认 result.getStatus() 是终态
        // Worker 返回 RUNNING / DISPATCHING 这种结果没有意义，应该拒绝
        if (!result.getStatus().isTerminal()) {
            throw new IllegalArgumentException("不允许修改");
        }
        //3: 走状态机
        stateMachine.transit(task, result.getStatus());

        //4: 写 result
        task.setResult(result);
        task.markUpdated();
    }



    @Override
    public void timeout(String taskId, String reason) {
        finishWithoutWorkerResult(taskId, TaskStatus.TIMEOUT, reason);
    }

    @Override
    public void cancel(String taskId, String reason) {
        finishWithoutWorkerResult(taskId, TaskStatus.CANCELED, reason);
    }
    private void finishWithoutWorkerResult (String taskId, TaskStatus status, String reason) {
        // TODO 1: requireTask
        TaskMetadata task = requireTask(taskId);
        // TODO 2: ensureNotTerminal
        ensureNotTerminal(task);
        // TODO 3: 构造 TaskResult
        TaskResult result = new TaskResult(
                task.getTaskId(),
                status,
                null,
                reason,
                task.getCreatedAt(),
                System.currentTimeMillis(),
                task.getAssignedWorkerId()
        );
        // TODO 4: stateMachine.transit(task, status)
        stateMachine.transit(task, status);
        // TODO 5: task.setResult(result)
        task.setResult(result);
        // TODO 6: markUpdated
        task.markUpdated();
    }

    private void ensureNotTerminal(TaskMetadata task) {
        if (task.getStatus().isTerminal()) {
            throw new IllegalStateException("Task is terminal can not change" + task.getTaskId());
        }
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

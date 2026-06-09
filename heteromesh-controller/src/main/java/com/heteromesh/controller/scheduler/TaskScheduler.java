package com.heteromesh.controller.scheduler;

import com.heteromesh.controller.task.TaskTimeoutManager;
import com.heteromesh.loadbalancer.LoadBalancer;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import com.heteromesh.task.TaskMetadata;
import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskStatus;
import com.heteromesh.task.TaskStore;

public class TaskScheduler {
    private final TaskStore taskStore;
    private final ServiceRegistry serviceRegistry;
    private final LoadBalancer loadBalancer;
    private final TaskTimeoutManager timeoutManager;


    public TaskScheduler(TaskStore taskStore, ServiceRegistry serviceRegistry, LoadBalancer loadBalancer) {
        this(taskStore, serviceRegistry, loadBalancer, null);
    }

    public TaskScheduler(TaskStore taskStore, ServiceRegistry serviceRegistry, LoadBalancer loadBalancer, TaskTimeoutManager timeoutManager) {
        this.taskStore = taskStore;
        this.serviceRegistry = serviceRegistry;
        this.loadBalancer = loadBalancer;
        this.timeoutManager = timeoutManager;
    }

    public ScheduleResult schedule(TaskRequest request) {
        TaskMetadata task = taskStore.create(request);
        String taskId = task.getTaskId();

        // 更新状态
        taskStore.updateStatus(taskId, TaskStatus.DISPATCHING);

        //如果没有可用 Worker，任务状态进入 FAILED，返回 failure
        if (serviceRegistry.size() == 0) {
            taskStore.updateStatus(taskId, TaskStatus.FAILED);
            return ScheduleResult.failure(task, "No available worker");
        }

        //用 loadBalancer.select(taskId) 选择 Worker
        ServiceInstance worker = loadBalancer.select(taskId);

        //如果选不到 Worker，任务状态进入 FAILED，返回 failure
        if (worker == null) {
            taskStore.updateStatus(taskId, TaskStatus.FAILED);
            return ScheduleResult.failure(task, "No worker selected");
        }
        //分配工作节点
        taskStore.assignWorker(taskId, worker.getNodeId());
        // 记录请求
        taskStore.recordAttempt(taskId, worker.getNodeId());

        // 注册超时
        if (timeoutManager != null) {
            timeoutManager.registerTimeout(task);
        }

        //返回 success(task, worker)
        return ScheduleResult.success(task, worker);
    }
}

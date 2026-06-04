# 第 17 课：Controller 任务调度器 TaskScheduler

---

## 一、本课目标

前两课我们已经有了：

```text
TaskRequest / TaskResult / TaskMetadata / TaskStatus / TaskStateMachine
TaskStore / InMemoryTaskStore
```

但它们还没有进入 Controller 调度链路。

这一课要新增 Controller 侧的任务调度器：

```text
TaskScheduler
```

它负责：

1. 接收 `TaskRequest`
2. 写入 `TaskStore`
3. 选择一个可用 Worker
4. 记录分配结果
5. 更新任务状态到 `DISPATCHING`
6. 返回调度结果给上层 Handler

这一课先不改 `ServerHandler` 主链路，先把调度器作为独立组件写出来并测通。

---

## 二、为什么需要 TaskScheduler

当前 `ServerHandler` 同时做了太多事：

```text
处理 REGISTER
处理 TASK_REQUEST
选择 Worker
维护 pendingClients
处理 TASK_RESPONSE
重试
熔断
回写响应
```

如果继续把任务调度逻辑塞进去，`ServerHandler` 会越来越大。

更好的分层是：

```text
ServerHandler:
  负责 Netty 消息入口和响应出口

TaskScheduler:
  负责任务创建、状态更新、Worker 选择

TaskStore:
  负责保存任务状态

LoadBalancer:
  负责从候选 Worker 中选节点
```

---

## 三、本课新增文件

放在：

`heteromesh-controller/src/main/java/com/heteromesh/controller/scheduler/`

| 文件 | 说明 |
|------|------|
| `ScheduleResult.java` | 一次调度的结果 |
| `TaskScheduler.java` | Controller 侧任务调度器 |

我来写测试：

`heteromesh-controller/src/test/java/com/heteromesh/controller/scheduler/TaskSchedulerTest.java`

---

## 四、ScheduleResult 设计

### 4.1 为什么需要 ScheduleResult

`TaskScheduler.schedule()` 不能只返回 `ServiceInstance`。

因为上层还需要知道：

- 任务元数据是什么
- 是否调度成功
- 失败原因是什么
- 选中的 Worker 是谁

所以用一个结果对象承载这些信息。

### 4.2 参考代码

位置：

`heteromesh-controller/src/main/java/com/heteromesh/controller/scheduler/ScheduleResult.java`

```java
package com.heteromesh.controller.scheduler;

import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.task.TaskMetadata;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScheduleResult {
    private boolean success;
    private TaskMetadata task;
    private ServiceInstance worker;
    private String errorMessage;

    public static ScheduleResult success(TaskMetadata task, ServiceInstance worker) {
        return new ScheduleResult(true, task, worker, null);
    }

    public static ScheduleResult failure(TaskMetadata task, String errorMessage) {
        return new ScheduleResult(false, task, null, errorMessage);
    }
}
```

---

## 五、TaskScheduler 设计

### 5.1 需要依赖什么

`TaskScheduler` 需要：

```java
private final TaskStore taskStore;
private final ServiceRegistry serviceRegistry;
private final LoadBalancer loadBalancer;
```

含义：

| 依赖 | 作用 |
|------|------|
| TaskStore | 创建和更新任务 |
| ServiceRegistry | 查询当前有哪些 Worker |
| LoadBalancer | 从 Worker 中选择目标节点 |

### 5.2 构造器

```java
public TaskScheduler(TaskStore taskStore,
                     ServiceRegistry serviceRegistry,
                     LoadBalancer loadBalancer) {
    this.taskStore = taskStore;
    this.serviceRegistry = serviceRegistry;
    this.loadBalancer = loadBalancer;
}
```

### 5.3 schedule 方法目标

方法签名：

```java
public ScheduleResult schedule(TaskRequest request)
```

逻辑：

```text
1. taskStore.create(request)
2. 如果没有可用 Worker，任务状态进入 FAILED，返回 failure
3. 用 loadBalancer.select(taskId) 选择 Worker
4. 如果选不到 Worker，任务状态进入 FAILED，返回 failure
5. taskStore.assignWorker(taskId, workerId)
6. taskStore.recordAttempt(taskId, workerId)
7. taskStore.updateStatus(taskId, DISPATCHING)
8. 返回 success(task, worker)
```

注意：

这节课先不把任务状态改成 `RUNNING`。  
`RUNNING` 应该发生在 Worker 接收并开始执行任务之后，下一阶段由 Worker 执行器负责。

---

## 六、TaskScheduler 代码骨架

位置：

`heteromesh-controller/src/main/java/com/heteromesh/controller/scheduler/TaskScheduler.java`

```java
package com.heteromesh.controller.scheduler;

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

    public TaskScheduler(TaskStore taskStore,
                         ServiceRegistry serviceRegistry,
                         LoadBalancer loadBalancer) {
        this.taskStore = taskStore;
        this.serviceRegistry = serviceRegistry;
        this.loadBalancer = loadBalancer;
    }

    public ScheduleResult schedule(TaskRequest request) {
        TaskMetadata task = taskStore.create(request);
        String taskId = task.getTaskId();

        if (serviceRegistry.list().isEmpty()) {
            taskStore.updateStatus(taskId, TaskStatus.FAILED);
            return ScheduleResult.failure(task, "No available worker");
        }

        ServiceInstance worker = loadBalancer.select(taskId);
        if (worker == null) {
            taskStore.updateStatus(taskId, TaskStatus.FAILED);
            return ScheduleResult.failure(task, "No worker selected");
        }

        taskStore.assignWorker(taskId, worker.getNodeId());
        taskStore.recordAttempt(taskId, worker.getNodeId());
        taskStore.updateStatus(taskId, TaskStatus.DISPATCHING);

        return ScheduleResult.success(task, worker);
    }
}
```

---

## 七、为什么这里要查 serviceRegistry

你可能会想：

```java
loadBalancer.select(taskId)
```

如果没有节点，它不是会返回 null 吗？为什么还要查 `serviceRegistry.list().isEmpty()`？

原因是语义更清楚：

```text
registry 为空：
  当前集群没有 Worker

loadBalancer 返回 null：
  可能是没有节点，也可能是策略内部无法选择
```

这节课先保留这个显式判断，便于测试和阅读。

---

## 八、测试设计（我来写）

测试覆盖：

| 测试名 | 内容 |
|--------|------|
| shouldScheduleTaskToWorker | 有 Worker 时成功调度 |
| shouldFailWhenNoWorkerAvailable | 没有 Worker 时任务失败 |
| shouldGenerateTaskIdWhenMissing | 没传 taskId 时自动生成 |
| shouldRecordAssignedWorkerAndAttempt | 调度成功后记录 assignedWorkerId 和 attemptedWorkers |
| shouldRejectDuplicateTaskId | 重复 taskId 不应覆盖已有任务 |

---

## 九、本课完成标准

- 新增 `ScheduleResult`
- 新增 `TaskScheduler`
- 调度成功时任务状态为 `DISPATCHING`
- 调度成功时记录 Worker
- 没有 Worker 时任务状态为 `FAILED`
- 测试通过


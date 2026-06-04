# 第 16 课：任务仓库 TaskStore

---

## 上下文（给新会话看）

- **项目**：HeteroMesh，从零手写分布式 RPC 框架，Java 21 + Netty + Maven 多模块
- **路径**：`C:\Users\12099\Desktop\HeteroMesh\HeteroMesh`
- **已完成**：
  - 自定义协议与协议防御
  - 节点注册、心跳、负载均衡
  - RPC 动态代理、超时、重试、熔断
  - `TaskRequest` / `TaskResult` / `TaskMetadata` / `TaskStatus` / `TaskStateMachine`
- **当前问题**：
  - 已经有任务模型，但 Controller 还没有地方保存任务
  - 后续 HTTP API 查询任务状态时，也没有查询入口

这一课要实现一个内存版任务仓库：`TaskStore`。

---

## 一、这节课解决什么问题？

### 1.1 当前状态

第 15 课后，我们有了任务对象：

```text
TaskRequest
TaskMetadata
TaskStatus
TaskResult
TaskStateMachine
```

但它们还只是模型。系统里还没有这种能力：

```java
TaskMetadata task = taskStore.create(request);
taskStore.updateStatus(taskId, TaskStatus.DISPATCHING);
TaskMetadata found = taskStore.get(taskId);
List<TaskMetadata> runningTasks = taskStore.listByStatus(TaskStatus.RUNNING);
```

也就是说，任务还“存不住”。

### 1.2 为什么要先做 TaskStore？

后面的 `TaskScheduler` 会依赖它：

```text
Client 提交任务
    ↓
Controller 创建 TaskMetadata
    ↓
TaskStore 保存任务
    ↓
TaskScheduler 派发任务
    ↓
Worker 返回结果
    ↓
TaskStore 更新结果
```

如果没有 TaskStore，调度器就只能拿局部变量传来传去，无法：

- 查询任务状态
- 统计运行中任务
- 记录失败重试
- 后续接 HTTP API
- 后续做故障恢复

---

## 二、参考主流项目怎么设计

### 2.1 XXL-JOB：调度日志就是任务仓库

XXL-JOB 会记录调度日志：

- 任务 ID
- 执行器地址
- 执行状态
- 触发时间
- 回调结果

这些日志不是附属品，而是调度系统能追踪任务的基础。

### 2.2 Kubernetes：API Server 保存对象状态

Kubernetes 的 Pod/Job 状态不是只存在调度器内存里，而是由 API Server / etcd 保存。调度器、控制器、用户查询都围绕这个状态源工作。

HeteroMesh 当前先用内存版 `ConcurrentHashMap`，以后可以换成 MySQL/Redis/etcd。

### 2.3 Redis / Kafka：先做内存原型，再抽接口

高质量项目通常会先定义接口：

```text
TaskStore
    └── InMemoryTaskStore
```

这样后面替换持久化实现时，不用改调度器核心逻辑。

---

## 三、本课目标

你要手敲：

| 文件 | 说明 |
|------|------|
| `TaskStore.java` | 任务仓库接口 |
| `InMemoryTaskStore.java` | 基于 ConcurrentHashMap 的内存实现 |

我来写：

| 文件 | 说明 |
|------|------|
| `InMemoryTaskStoreTest.java` | 测试创建、查询、状态更新、结果更新、按状态过滤 |

---

## 四、TaskStore 接口设计

位置：

`heteromesh-common/src/main/java/com/heteromesh/task/TaskStore.java`

参考代码：

```java
package com.heteromesh.task;

import java.util.List;
import java.util.Optional;

/**
 * 任务仓库接口。
 *
 * 当前只有内存实现，后续可以替换成 MySQL / Redis / etcd。
 */
public interface TaskStore {

    /**
     * 创建并保存任务元数据。
     */
    TaskMetadata create(TaskRequest request);

    /**
     * 根据 taskId 查询任务。
     */
    Optional<TaskMetadata> get(String taskId);

    /**
     * 更新任务状态。
     */
    void updateStatus(String taskId, TaskStatus status);

    /**
     * 绑定任务被派发到的 Worker。
     */
    void assignWorker(String taskId, String workerId);

    /**
     * 记录一次尝试过的 Worker，并增加 retryCount。
     */
    void recordAttempt(String taskId, String workerId);

    /**
     * 写入任务结果。
     */
    void complete(String taskId, TaskResult result);

    /**
     * 列出全部任务。
     */
    List<TaskMetadata> listAll();

    /**
     * 按状态过滤任务。
     */
    List<TaskMetadata> listByStatus(TaskStatus status);
}
```

---

## 五、InMemoryTaskStore 实现

位置：

`heteromesh-common/src/main/java/com/heteromesh/task/InMemoryTaskStore.java`

### 5.1 数据结构

```java
private final ConcurrentHashMap<String, TaskMetadata> tasks = new ConcurrentHashMap<>();
private final TaskStateMachine stateMachine = new TaskStateMachine();
```

为什么用 `ConcurrentHashMap`？

- Controller 是 Netty 多线程处理请求
- 多个请求可能同时创建/更新任务
- 普通 `HashMap` 不适合并发读写

### 5.2 create

参考代码：

```java
public TaskMetadata create(TaskRequest request) {
    String taskId = request.getTaskId();
    if (taskId == null || taskId.isBlank()) {
        taskId = UUID.randomUUID().toString();
        request.setTaskId(taskId);
    }

    TaskMetadata metadata = new TaskMetadata(taskId, request);
    TaskMetadata existing = tasks.putIfAbsent(taskId, metadata);
    if (existing != null) {
        throw new IllegalArgumentException("Task already exists: " + taskId);
    }
    return metadata;
}
```

关键点：

- 客户端没传 taskId 时，Controller 生成
- 用 `putIfAbsent` 防止重复任务覆盖
- 不要用 `tasks.put`，否则同 ID 任务会被悄悄覆盖

### 5.3 get

```java
public Optional<TaskMetadata> get(String taskId) {
    return Optional.ofNullable(tasks.get(taskId));
}
```

为什么返回 `Optional`？

- 明确表达“任务可能不存在”
- 调用方不用靠 null 猜

### 5.4 updateStatus

```java
public void updateStatus(String taskId, TaskStatus status) {
    TaskMetadata task = requireTask(taskId);
    stateMachine.transit(task, status);
}
```

这里用状态机，不要直接：

```java
task.setStatus(status);
```

因为直接 set 会绕过状态流转规则。

### 5.5 assignWorker

```java
public void assignWorker(String taskId, String workerId) {
    TaskMetadata task = requireTask(taskId);
    task.setAssignedWorkerId(workerId);
    task.markUpdated();
}
```

### 5.6 recordAttempt

```java
public void recordAttempt(String taskId, String workerId) {
    TaskMetadata task = requireTask(taskId);
    task.getAttemptedWorkers().add(workerId);
    task.setRetryCount(task.getRetryCount() + 1);
    task.markUpdated();
}
```

注意：

`retryCount` 的含义要统一。这里定义为“尝试次数记录”，后续如果要区分第一次派发和重试，可以再改成 `attemptCount` / `retryCount` 两个字段。

### 5.7 complete

```java
public void complete(String taskId, TaskResult result) {
    TaskMetadata task = requireTask(taskId);
    task.setResult(result);
    stateMachine.transit(task, result.getStatus());
}
```

注意：

`result.getStatus()` 应该是终态，比如：

- `SUCCEEDED`
- `FAILED`
- `TIMEOUT`

不要用 `RUNNING` 作为结果状态。

### 5.8 listAll / listByStatus

```java
public List<TaskMetadata> listAll() {
    return new ArrayList<>(tasks.values());
}

public List<TaskMetadata> listByStatus(TaskStatus status) {
    return tasks.values().stream()
            .filter(task -> task.getStatus() == status)
            .toList();
}
```

---

## 六、你要手敲的完整骨架

```java
package com.heteromesh.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTaskStore implements TaskStore {
    private final ConcurrentHashMap<String, TaskMetadata> tasks = new ConcurrentHashMap<>();
    private final TaskStateMachine stateMachine = new TaskStateMachine();

    @Override
    public TaskMetadata create(TaskRequest request) {
        // TODO
        return null;
    }

    @Override
    public Optional<TaskMetadata> get(String taskId) {
        // TODO
        return Optional.empty();
    }

    @Override
    public void updateStatus(String taskId, TaskStatus status) {
        // TODO
    }

    @Override
    public void assignWorker(String taskId, String workerId) {
        // TODO
    }

    @Override
    public void recordAttempt(String taskId, String workerId) {
        // TODO
    }

    @Override
    public void complete(String taskId, TaskResult result) {
        // TODO
    }

    @Override
    public List<TaskMetadata> listAll() {
        // TODO
        return List.of();
    }

    @Override
    public List<TaskMetadata> listByStatus(TaskStatus status) {
        // TODO
        return List.of();
    }

    private TaskMetadata requireTask(String taskId) {
        TaskMetadata task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return task;
    }
}
```

---

## 七、常见错误预警

### 错误 1：直接 setStatus

```java
// 不推荐
task.setStatus(status);

// 推荐
stateMachine.transit(task, status);
```

直接 set 会绕过状态机。

### 错误 2：重复 taskId 被覆盖

```java
// 不推荐
tasks.put(taskId, metadata);

// 推荐
tasks.putIfAbsent(taskId, metadata);
```

任务 ID 是幂等和查询的基础，不能悄悄覆盖。

### 错误 3：返回内部集合

```java
// 不推荐暴露 values 视图
return tasks.values();

// 推荐返回快照
return new ArrayList<>(tasks.values());
```

否则调用方可能观察到并发变化，甚至误以为可以修改仓库内部状态。

---

## 八、测试设计（我来写）

测试文件：

`heteromesh-common/src/test/java/com/heteromesh/task/InMemoryTaskStoreTest.java`

测试覆盖：

| 测试名 | 测什么 |
|--------|--------|
| shouldCreateTaskWithProvidedId | 使用客户端传入的 taskId 创建任务 |
| shouldGenerateTaskIdWhenMissing | taskId 为空时自动生成 |
| shouldRejectDuplicateTaskId | 重复 taskId 不能覆盖 |
| shouldUpdateStatusThroughStateMachine | 状态更新必须遵守状态机 |
| shouldAssignWorkerAndRecordAttempt | 能记录 assignedWorkerId、attemptedWorkers、retryCount |
| shouldCompleteTaskWithResult | 写入结果并进入终态 |
| shouldListByStatus | 能按状态过滤任务 |
| shouldRejectMissingTaskUpdate | 更新不存在任务时抛异常 |

---

## 九、面试怎么讲

可以这样讲：

> 我在任务模型之后抽象了 TaskStore，用接口隔离任务持久化。当前实现是 InMemoryTaskStore，底层用 ConcurrentHashMap，支持创建任务、查询任务、更新状态、记录 Worker 尝试、写入结果和按状态过滤。状态更新统一经过 TaskStateMachine，避免业务代码绕过合法状态流转。后续如果要接 MySQL、Redis 或 etcd，只需要替换 TaskStore 实现，调度器不用改。

如果面试官问：

> 为什么现在不用数据库？

可以回答：

> 当前阶段重点是先把调度语义跑通，所以用内存仓库降低复杂度。接口已经抽出来了，后续持久化只需要实现 TaskStore，不影响 TaskScheduler。

---

## 十、本课完成标准

- 新增 `TaskStore`
- 新增 `InMemoryTaskStore`
- 创建任务时支持自动生成 taskId
- 重复 taskId 会被拒绝
- 状态更新走 `TaskStateMachine`
- 支持记录 Worker 分配和尝试历史
- 支持写入结果
- 支持按状态过滤
- 全量 `mvn test` 通过


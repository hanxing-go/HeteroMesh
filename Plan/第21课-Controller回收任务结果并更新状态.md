# 第21课：Controller 回收任务结果并更新状态

---

## 一、本课目标

前两课我们已经完成了任务调度链路的两个关键部分：

```text
第19课：Controller 接收 TASK_SUBMIT，并转发给 Worker
第20课：Worker 接收 TASK_SUBMIT，执行任务，并返回 TASK_RESULT
```

现在链路已经能跑到这里：

```text
Client
  -> Controller
  -> Worker
  -> Controller
  -> Client
```

但还有一个重要问题：

```text
Controller 目前只是把 TASK_RESULT 转发给 Client
并没有把任务最终结果写回 TaskStore
```

这会导致：

```text
任务执行完了，但 Controller 自己不知道最终结果
后续没法查询任务状态
也没法展示任务历史、耗时、失败原因
```

本课要完成的是：

```text
Controller 收到 TASK_RESULT
        ↓
解析 TaskResult
        ↓
更新 TaskStore 中对应任务状态和结果
        ↓
再把 TASK_RESULT 回传给 Client
```

---

## 二、为什么这一课很重要

之前你问过：

```text
既然 RPC 能远程调用方法，为什么还要任务调度系统？
```

这一课就是任务调度系统和普通 RPC 最大的区别之一：

```text
RPC 只关心这次调用有没有回包
任务系统要记录这个业务任务最后到底怎么样了
```

比如图片处理任务：

```text
taskId = image-task-001
status = SUCCEEDED
workerId = worker-a
output = 结果文件地址
startedAt = ...
finishedAt = ...
```

这些都应该留在 `TaskStore` 里。

否则用户刷新页面后，系统就不知道这个任务执行结果了。

---

## 三、本课要修改的文件

你手写核心代码：

| 文件 | 作用 |
|------|------|
| `heteromesh-controller/src/main/java/com/heteromesh/controller/ServerHandler.java` | 注入 `TaskStore`，在 `handleTaskResult` 中写回任务结果 |
| `heteromesh-controller/src/main/java/com/heteromesh/controller/HeteroMeshServer.java` | 创建 `TaskStore` 变量，并同时传给 `TaskScheduler` 和 `ServerHandler` |
| `heteromesh-controller/src/test/java/com/heteromesh/controller/ServerHandlerTaskSubmitTest.java` | 旧测试构造器适配，我来改也可以 |
| 其他旧测试 | 如果 `new ServerHandler(...)` 编译失败，需要补传 `TaskStore` |

我后面补测试：

| 文件 | 作用 |
|------|------|
| `heteromesh-controller/src/test/java/com/heteromesh/controller/ServerHandlerTaskResultStoreTest.java` | 验证 Controller 收到 `TASK_RESULT` 后会更新 `TaskStore` |

---

## 四、第一步：理解当前问题

当前 `ServerHandler.handleTaskResult` 大概是：

```java
private void handleTaskResult(ChannelHandlerContext ctx, Message message) {
    Channel clientChannel = pendingClients.remove(message.getRequestId());

    if (clientChannel != null && clientChannel.isActive()) {
        clientChannel.writeAndFlush(message);
    } else {
        log.warn("找不到任务提交方: requestId={}", message.getRequestId());
    }
}
```

它只做了：

```text
按 requestId 找回 Client Channel
把 Worker 返回的 TASK_RESULT 转发给 Client
```

它没做：

```text
TaskPayloadCodec.decodeResult(message.getBody())
taskStore.complete(taskId, result)
```

---

## 五、第二步：为什么 ServerHandler 要持有 TaskStore

现在 `ServerHandler` 持有：

```java
private final TaskScheduler taskScheduler;
```

但 `TaskScheduler` 只负责：

```text
创建任务
选择 Worker
把任务推进到 DISPATCHING
```

结果回收不应该再塞回 `TaskScheduler`。

因为 `TASK_RESULT` 到达时，调度动作已经结束了。

所以本课让 `ServerHandler` 显式持有：

```java
private final TaskStore taskStore;
```

职责变成：

```text
TASK_SUBMIT -> 调 TaskScheduler
TASK_RESULT -> 写 TaskStore
```

这比让 `TaskScheduler` 什么都管更清楚。

---

## 六、第三步：改 ServerHandler 构造器

打开：

```text
heteromesh-controller/src/main/java/com/heteromesh/controller/ServerHandler.java
```

新增 import：

```java
import com.heteromesh.task.TaskStore;
```

新增字段：

```java
private final TaskStore taskStore;
```

构造器从：

```java
public ServerHandler(ServiceRegistry registry,
                     NodeChannelMap nodeChannelMap,
                     LoadBalancer loadBalancer,
                     Map<String, Channel> pendingClients,
                     TaskScheduler taskScheduler) {
```

改成：

```java
public ServerHandler(ServiceRegistry registry,
                     NodeChannelMap nodeChannelMap,
                     LoadBalancer loadBalancer,
                     Map<String, Channel> pendingClients,
                     TaskScheduler taskScheduler,
                     TaskStore taskStore) {
```

然后赋值：

```java
this.taskScheduler = taskScheduler;
this.taskStore = taskStore;
```

---

## 七、第四步：改 HeteroMeshServer

打开：

```text
heteromesh-controller/src/main/java/com/heteromesh/controller/HeteroMeshServer.java
```

现在你可能写的是：

```java
TaskScheduler scheduler = new TaskScheduler(new InMemoryTaskStore(), sr, loadBalancer);
```

这有一个隐患：

```text
TaskStore 是匿名创建的，ServerHandler 拿不到同一个对象
```

所以要改成：

```java
TaskStore taskStore = new InMemoryTaskStore();
TaskScheduler scheduler = new TaskScheduler(taskStore, sr, loadBalancer);
```

然后构造 `ServerHandler` 时传进去：

```java
new ServerHandler(sr, ncm, loadBalancer, pendingClients, scheduler, taskStore)
```

注意：

```text
TaskScheduler 和 ServerHandler 必须使用同一个 TaskStore 实例
```

否则 Scheduler 创建的任务，Handler 回收结果时会查不到。

---

## 八、第五步：改 handleTaskResult

目标逻辑：

```text
1. 解码 TaskResult
2. 用 taskId 更新 TaskStore
3. 再按 requestId 找 Client 并回传
```

参考代码：

```java
private void handleTaskResult(ChannelHandlerContext ctx, Message message) {
    TaskResult result = TaskPayloadCodec.decodeResult(message.getBody());

    try {
        taskStore.complete(result.getTaskId(), result);
    } catch (Exception e) {
        log.warn("更新任务结果失败: taskId={}, requestId={}",
                result.getTaskId(), message.getRequestId(), e);
    }

    Channel clientChannel = pendingClients.remove(message.getRequestId());

    if (clientChannel != null && clientChannel.isActive()) {
        clientChannel.writeAndFlush(message);
    } else {
        log.warn("找不到任务提交方: requestId={}", message.getRequestId());
    }
}
```

这里先用 `try/catch` 是为了避免：

```text
TaskStore 更新失败导致 Client 永远收不到 Worker 结果
```

但要注意，这不是最终完美设计。

后续我们可以把它升级为：

```text
结果落库失败时，返回一个明确的框架错误
或者进入补偿队列
```

本课先保证链路稳定。

---

## 九、状态流转的关键点

这里你可能会遇到一个状态机问题。

当前 Scheduler 成功调度后，任务状态是：

```text
DISPATCHING
```

但 Worker 返回的结果通常是：

```text
SUCCEEDED
FAILED
```

而你的状态机当前允许：

```text
DISPATCHING -> RUNNING
RUNNING -> SUCCEEDED / FAILED
```

如果直接：

```java
taskStore.complete(taskId, result);
```

并且 `result.status = SUCCEEDED`，可能会出现：

```text
DISPATCHING can not transit to SUCCEEDED
```

本课推荐一个简单处理：

```text
Controller 在收到 Worker 的 TASK_RESULT 前，认为 Worker 已经开始执行过
所以如果任务还处于 DISPATCHING，先转成 RUNNING，再 complete
```

也就是在 `handleTaskResult` 中：

```java
TaskMetadata metadata = taskStore.get(result.getTaskId()).orElse(null);
if (metadata != null && metadata.getStatus() == TaskStatus.DISPATCHING) {
    taskStore.updateStatus(result.getTaskId(), TaskStatus.RUNNING);
}
taskStore.complete(result.getTaskId(), result);
```

需要新增 import：

```java
import com.heteromesh.task.TaskMetadata;
```

这不是最终最精细的状态设计，但符合现在的实现阶段。

更完整的设计是下一阶段做：

```text
Worker 接收任务后先 ACK_RUNNING
执行完成后再 TASK_RESULT
```

---

## 十、本课完成后的链路

完成后链路变成：

```text
Client
  -> TASK_SUBMIT(TaskRequest)

Controller
  -> TaskStore.create
  -> status = DISPATCHING
  -> 转发给 Worker

Worker
  -> execute
  -> TASK_RESULT(TaskResult)

Controller
  -> status = RUNNING
  -> status = SUCCEEDED / FAILED
  -> 保存 TaskResult
  -> 回传 Client
```

---

## 十一、本课完成标准

你写完后应该满足：

- `ServerHandler` 持有 `TaskStore`
- `HeteroMeshServer` 中 `TaskScheduler` 和 `ServerHandler` 使用同一个 `TaskStore`
- Controller 收到 `TASK_RESULT` 后能解析 `TaskResult`
- Controller 能把任务状态更新为最终状态
- Controller 能把 `TaskResult` 保存进 `TaskMetadata`
- Controller 仍然会把 `TASK_RESULT` 回传给 Client
- 旧 RPC 的 `TASK_REQUEST / TASK_RESPONSE` 不受影响

---

## 十二、我后面会补的测试

你写完后，我会补：

```text
ServerHandlerTaskResultStoreTest
```

测试重点：

```text
1. 提交任务后，TaskStore 中出现任务
2. Worker 返回 SUCCEEDED 后，TaskStore 状态变为 SUCCEEDED
3. TaskMetadata.result 被正确写入
4. Client 仍然能收到 TASK_RESULT
5. Worker 返回 FAILED 后，TaskStore 状态变为 FAILED
```


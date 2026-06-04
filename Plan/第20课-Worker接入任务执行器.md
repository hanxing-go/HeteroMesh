# 第20课：Worker 接入任务执行器

---

## 一、本课目标

上一课我们已经让 Controller 能处理新的任务调度消息：

```text
Client -> Controller: TASK_SUBMIT
Controller -> Worker: TASK_SUBMIT
```

但目前 Worker 侧的 `ClientHandler` 主要只处理旧 RPC 链路：

```java
TASK_REQUEST
TASK_RESPONSE
REGISTER_ACK
```

也就是说，Controller 虽然能把 `TASK_SUBMIT` 转发到 Worker，但 Worker 还不会真正执行它。

本课要完成的闭环是：

```text
Worker 收到 TASK_SUBMIT
        ↓
解析 TaskRequest
        ↓
交给本地 TaskExecutor 执行
        ↓
生成 TaskResult
        ↓
返回 TASK_RESULT 给 Controller
```

完成后，任务调度链路就从“能派发”进入“能执行”。

---

## 二、本课核心设计

不要把任务执行逻辑直接写死在 `ClientHandler` 里。

原因是：

```text
ClientHandler 负责网络消息处理
TaskExecutor 负责业务任务执行
```

这是一条很重要的工程边界。

如果以后你支持更多任务类型，比如：

```text
IMAGE_PROCESS
TEXT_GENERATION
VIDEO_TRANSCODE
GPU_BENCHMARK
```

就可以继续扩展不同的执行器，而不是把所有逻辑塞进 Netty Handler。

本课先实现一个默认执行器：

```text
TaskExecutor
    ↑
DefaultTaskExecutor
```

它暂时不接真实 AI 模型，只返回一段模拟执行结果，先把链路跑通。

---

## 三、本课要修改的文件

你手写核心代码：

| 文件 | 作用 |
|------|------|
| `heteromesh-worker/src/main/java/com/heteromesh/worker/TaskExecutor.java` | 定义 Worker 执行任务的接口 |
| `heteromesh-worker/src/main/java/com/heteromesh/worker/DefaultTaskExecutor.java` | 默认任务执行器实现 |
| `heteromesh-worker/src/main/java/com/heteromesh/worker/ClientHandler.java` | 接收 `TASK_SUBMIT` 并返回 `TASK_RESULT` |
| `heteromesh-worker/src/main/java/com/heteromesh/worker/WorkerClient.java` | 创建并注入 `TaskExecutor` |

我后面补测试：

| 文件 | 作用 |
|------|------|
| `heteromesh-worker/src/test/java/com/heteromesh/worker/ClientHandlerTaskSubmitTest.java` | 验证 Worker 能处理任务提交消息 |

---

## 四、第一步：新增 TaskExecutor 接口

新增文件：

```text
heteromesh-worker/src/main/java/com/heteromesh/worker/TaskExecutor.java
```

代码：

```java
package com.heteromesh.worker;

import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;

public interface TaskExecutor {

    TaskResult execute(TaskRequest request);
}
```

这个接口表达的是：

```text
给 Worker 一个 TaskRequest
Worker 执行后返回 TaskResult
```

注意：`TaskExecutor` 不关心网络，也不关心 `Message.requestId`。

它只关心业务任务本身。

---

## 五、第二步：新增 DefaultTaskExecutor

新增文件：

```text
heteromesh-worker/src/main/java/com/heteromesh/worker/DefaultTaskExecutor.java
```

代码示例：

```java
package com.heteromesh.worker;

import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;
import com.heteromesh.task.TaskStatus;

public class DefaultTaskExecutor implements TaskExecutor {

    private final String nodeId;

    public DefaultTaskExecutor(String nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    public TaskResult execute(TaskRequest request) {
        long startedAt = System.currentTimeMillis();

        try {
            String output = "Task " + request.getTaskId()
                    + " executed by " + nodeId
                    + ", type=" + request.getTaskType()
                    + ", payload=" + request.getPayload();

            return new TaskResult(
                    request.getTaskId(),
                    TaskStatus.SUCCEEDED,
                    output,
                    null,
                    startedAt,
                    System.currentTimeMillis(),
                    nodeId
            );
        } catch (Exception e) {
            return new TaskResult(
                    request.getTaskId(),
                    TaskStatus.FAILED,
                    null,
                    e.getMessage(),
                    startedAt,
                    System.currentTimeMillis(),
                    nodeId
            );
        }
    }
}
```

这只是一个临时默认实现。

它的意义不是“真的处理图片”，而是让系统先拥有完整任务执行闭环。

后续可以演进为：

```text
TaskExecutor
    ↑
TaskExecutorRouter
        ├── ImageProcessExecutor
        ├── TextGenerationExecutor
        └── VideoTranscodeExecutor
```

---

## 六、第三步：改 ClientHandler 构造器

打开：

```text
heteromesh-worker/src/main/java/com/heteromesh/worker/ClientHandler.java
```

新增字段：

```java
private final TaskExecutor taskExecutor;
```

把构造器从：

```java
public ClientHandler(RpcClient client, String nodeId, RpcDispatcher dispatcher) {
    this.rpcClient = client;
    this.nodeId = nodeId;
    this.dispatcher = dispatcher;
}
```

改成：

```java
public ClientHandler(RpcClient client,
                     String nodeId,
                     RpcDispatcher dispatcher,
                     TaskExecutor taskExecutor) {
    this.rpcClient = client;
    this.nodeId = nodeId;
    this.dispatcher = dispatcher;
    this.taskExecutor = taskExecutor;
}
```

---

## 七、第四步：让 Worker 识别 TASK_SUBMIT

在 `ClientHandler.channelRead0` 的 `switch` 里新增分支。

当前大概是：

```java
switch (msg.getType()) {
    case TASK_RESPONSE -> rpcClient.onResponse(msg);
    case TASK_REQUEST -> handleTaskRequest(ctx, msg);
    case REGISTER_ACK -> log.info("注册确认: {}", msg.getBody());
    default -> log.debug("收到回复: {}", msg.getBody());
}
```

新增：

```java
case TASK_SUBMIT -> handleTaskSubmit(ctx, msg);
```

注意：

```text
TASK_REQUEST 仍然走旧 RPC 链路
TASK_SUBMIT 才走新任务调度链路
```

不要把两个分支混在一起。

---

## 八、第五步：新增 handleTaskSubmit

在 `ClientHandler` 中新增方法：

```java
private void handleTaskSubmit(ChannelHandlerContext ctx, Message msg) {
    TaskRequest request = TaskPayloadCodec.decodeRequest(msg.getBody());
    TaskResult result = taskExecutor.execute(request);

    Message response = Message.createTaskResult(
            msg.getRequestId(),
            TaskPayloadCodec.encodeResult(result)
    );

    ctx.writeAndFlush(response);
}
```

这里最重要的是两个 ID 不要搞混：

```text
Message.requestId：一次网络请求的回执单号，用来让 Controller 找回 Client
TaskRequest.taskId：业务任务 ID，用来标识这个任务本身
```

所以返回时必须保持：

```java
msg.getRequestId()
```

而 `TaskResult` 里面的 `taskId` 来自：

```java
request.getTaskId()
```

---

## 九、第六步：改 WorkerClient 注入执行器

打开：

```text
heteromesh-worker/src/main/java/com/heteromesh/worker/WorkerClient.java
```

在创建 `ClientHandler` 之前，先创建默认执行器：

```java
TaskExecutor taskExecutor = new DefaultTaskExecutor(nodeId);
```

然后把：

```java
new ClientHandler(rpcClient, nodeId, dispatcher)
```

改成：

```java
new ClientHandler(rpcClient, nodeId, dispatcher, taskExecutor)
```

这样 Worker 启动时就拥有任务执行能力。

---

## 十、本课完成后的链路

完成后完整链路应该是：

```text
Client
  -> TASK_SUBMIT(TaskRequest JSON)

Controller
  -> TaskScheduler 选择 Worker
  -> 转发 TASK_SUBMIT

Worker
  -> TaskPayloadCodec.decodeRequest
  -> TaskExecutor.execute
  -> TASK_RESULT(TaskResult JSON)

Controller
  -> 根据 requestId 回传 Client
```

这个阶段还没有把最终结果写回 Controller 的 `TaskStore`。

这件事放到下一课：

```text
Controller 回收 TASK_RESULT 并更新任务状态
```

---

## 十一、本课完成标准

你写完后应该满足：

- Worker 能识别 `TASK_SUBMIT`
- Worker 能解析 `TaskRequest`
- Worker 能调用 `TaskExecutor`
- Worker 能返回 `TASK_RESULT`
- `TASK_RESULT.requestId` 和原 `TASK_SUBMIT.requestId` 一致
- `TaskResult.taskId` 和原 `TaskRequest.taskId` 一致
- 旧 RPC 的 `TASK_REQUEST / TASK_RESPONSE` 不受影响

---

## 十二、我后面会补的测试

你写完后，我会补：

```text
ClientHandlerTaskSubmitTest
```

测试重点：

```text
1. Worker 收到 TASK_SUBMIT 后返回 TASK_RESULT
2. 返回消息保持原 requestId
3. TaskResult 中的 taskId / workerId / status 正确
4. 旧 RPC 分支仍然不受影响
```


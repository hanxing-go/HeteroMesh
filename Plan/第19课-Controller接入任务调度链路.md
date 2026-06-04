# 第 19 课：Controller 接入任务调度链路

---

## 一、本课目标

前面我们已经有了任务调度相关的基础组件：

```text
TaskRequest / TaskResult / TaskMetadata / TaskStatus
TaskStore / InMemoryTaskStore
TaskScheduler
TaskPayloadCodec
```

但它们目前还没有真正进入 Controller 的网络链路。

这一课要做的事情是：

```text
让 Controller 在收到任务提交消息时：
1. 解析 TaskRequest
2. 调用 TaskScheduler 创建任务并选择 Worker
3. 把任务消息转发给选中的 Worker
4. 在调度失败时直接返回失败响应
```

注意：这一课要非常小心，**不能破坏现有 RPC 动态代理链路**。

---

## 二、为什么不能直接改原来的 TASK_REQUEST

当前 `TASK_REQUEST` 已经被 RPC 链路使用：

```text
RpcProxy -> RpcClient -> TASK_REQUEST(RpcRequest JSON) -> Controller -> Worker -> RpcDispatcher
```

如果我们直接把 `TASK_REQUEST` 改成只解析 `TaskRequest`，那么已有 RPC 测试和 Demo 会坏。

所以这一课采用更稳的做法：

```text
新增 MessageType：
TASK_SUBMIT
TASK_RESULT
```

含义：

| MessageType | 用途 |
|-------------|------|
| TASK_REQUEST | 保留给现有 RPC 调用链路 |
| TASK_RESPONSE | 保留给现有 RPC 响应链路 |
| TASK_SUBMIT | 新增，表示提交调度任务 |
| TASK_RESULT | 新增，表示 Worker 回传任务执行结果 |

这样 RPC 和任务调度就不会混在一起。

---

## 三、本课要改哪些文件

### 你手敲核心代码

| 文件 | 修改内容 |
|------|----------|
| `MessageType.java` | 新增 `TASK_SUBMIT`、`TASK_RESULT` |
| `application.yml` | 给新消息类型配置序列化方式 |
| `Message.java` | 增加创建任务提交/结果消息的静态工厂方法 |
| `ServerHandler.java` | 新增 `TASK_SUBMIT` 分支，调用 `TaskScheduler` |
| `HeteroMeshServer.java` | 初始化 `TaskStore` 和 `TaskScheduler` 并传给 `ServerHandler` |

### 暂时不做

这一课暂时不写 Worker 真执行器。Worker 收到 `TASK_SUBMIT` 后怎么执行，放到下一课。

所以本课 Controller 侧转发出去即可，测试可以用 fake Worker 接收消息来验证。

---

## 四、第一步：扩展 MessageType

位置：

`heteromesh-common/src/main/java/com/heteromesh/protocol/MessageType.java`

当前已有：

```java
TASK_REQUEST((byte) 0x10),
TASK_RESPONSE((byte) 0x11);
```

你新增：

```java
TASK_SUBMIT((byte) 0x20),      // 调度任务提交
TASK_RESULT((byte) 0x21);      // 调度任务结果
```

注意最后一个枚举值才用分号。

最终大概是：

```java
TASK_REQUEST((byte) 0x10),
TASK_RESPONSE((byte) 0x11),
TASK_SUBMIT((byte) 0x20),
TASK_RESULT((byte) 0x21);
```

---

## 五、第二步：更新 YAML 序列化路由

位置：

`heteromesh-common/src/main/resources/application.yml`

在 routing 中加入：

```yaml
TASK_SUBMIT: json
TASK_RESULT: json
```

任务请求/结果先用 JSON，方便调试。

---

## 六、第三步：扩展 Message 工厂方法

位置：

`heteromesh-common/src/main/java/com/heteromesh/protocol/Message.java`

你可以新增：

```java
public static Message createTaskSubmit(String body) {
    return new Message(MessageType.TASK_SUBMIT, UUID.randomUUID().toString(), body);
}

public static Message createTaskResult(String requestId, String body) {
    return new Message(MessageType.TASK_RESULT, requestId, body);
}
```

这里的 `requestId` 仍然是网络请求 ID，用来让 Controller 把结果回给提交方。

任务本身的 `taskId` 在 `TaskRequest.body` 内部。

---

## 七、第四步：改造 ServerHandler 构造器

当前 `ServerHandler` 有这些依赖：

```java
private final ServiceRegistry registry;
private final NodeChannelMap nodeChannelMap;
private final LoadBalancer loadBalancer;
private final Map<String, Channel> pendingClients;
```

这一课新增：

```java
private final TaskScheduler taskScheduler;
```

构造器也加上：

```java
public ServerHandler(ServiceRegistry registry,
                     NodeChannelMap nodeChannelMap,
                     LoadBalancer loadBalancer,
                     Map<String, Channel> pendingClients,
                     TaskScheduler taskScheduler) {
    this.registry = registry;
    this.nodeChannelMap = nodeChannelMap;
    this.loadBalancer = loadBalancer;
    this.pendingClients = pendingClients;
    this.taskScheduler = taskScheduler;
}
```

---

## 八、第五步：ServerHandler 增加 TASK_SUBMIT 分支

当前分支大概是：

```java
switch (message.getType()) {
    case REGISTER -> handleRegister(ctx, message);
    case TASK_REQUEST -> handleTaskRequest(ctx, message);
    case TASK_RESPONSE -> handleTaskResponse(ctx, message);
    default -> log.debug("收到消息: type = {}", message.getType());
}
```

新增：

```java
case TASK_SUBMIT -> handleTaskSubmit(ctx, message);
case TASK_RESULT -> handleTaskResult(ctx, message);
```

这一课先实现 `handleTaskSubmit`。

`handleTaskResult` 可以先只转发回客户端，下一课 Worker 执行器再完善。

---

## 九、handleTaskSubmit 设计

核心逻辑：

```text
1. 从 msg.body 解析 TaskRequest
2. 调用 taskScheduler.schedule(request)
3. 如果失败：
   - 构造 TaskResult(status=FAILED)
   - createTaskResult(msg.requestId, resultJson)
   - 直接 ctx.writeAndFlush
4. 如果成功：
   - 找到 worker Channel
   - 保存 pendingClients：requestId -> clientChannel
   - 转发原始 TASK_SUBMIT 给 Worker
```

参考代码骨架：

```java
private void handleTaskSubmit(ChannelHandlerContext ctx, Message msg) {
    TaskRequest request = TaskPayloadCodec.decodeRequest(msg.getBody());
    ScheduleResult result = taskScheduler.schedule(request);

    if (!result.isSuccess()) {
        TaskResult failed = new TaskResult(
                result.getTask().getTaskId(),
                TaskStatus.FAILED,
                null,
                result.getErrorMessage(),
                0,
                System.currentTimeMillis(),
                null
        );
        Message reply = Message.createTaskResult(
                msg.getRequestId(),
                TaskPayloadCodec.encodeResult(failed)
        );
        ctx.writeAndFlush(reply);
        return;
    }

    Channel workerChannel = nodeChannelMap.getChannel(result.getWorker().getNodeId());
    if (workerChannel == null || !workerChannel.isActive()) {
        // TODO: 本课先返回失败；后续第 21 课做任务级重试
    }

    pendingClients.put(msg.getRequestId(), ctx.channel());
    workerChannel.writeAndFlush(msg);
}
```

这个方法里你会遇到一个设计点：

```text
TaskScheduler 选到了 Worker
但 NodeChannelMap 找不到 Channel
```

这一课先简单返回失败。任务级重试下一阶段再做。

---

## 十、handleTaskResult 设计

先做最小版本：

```java
private void handleTaskResult(ChannelHandlerContext ctx, Message msg) {
    Channel clientChannel = pendingClients.remove(msg.getRequestId());
    if (clientChannel != null && clientChannel.isActive()) {
        clientChannel.writeAndFlush(msg);
    } else {
        log.warn("找不到任务提交方: requestId={}", msg.getRequestId());
    }
}
```

注意：

这一课还没有把结果写回 `TaskStore`，因为我们先做消息转发闭环。  
下一课 Worker 执行器和结果回收一起完善。

---

## 十一、HeteroMeshServer 初始化 TaskScheduler

位置：

`heteromesh-controller/src/main/java/com/heteromesh/controller/HeteroMeshServer.java`

在创建 `ServiceRegistry`、`NodeChannelMap`、`LoadBalancer` 后，新增：

```java
TaskStore taskStore = new InMemoryTaskStore();
TaskScheduler taskScheduler = new TaskScheduler(sr, ncm, loadBalancer ...);
```

注意构造器实际参数应该是：

```java
TaskScheduler taskScheduler = new TaskScheduler(taskStore, sr, loadBalancer);
```

然后创建 `ServerHandler` 时多传一个参数：

```java
new ServerHandler(sr, ncm, loadBalancer, pendingClients, taskScheduler)
```

---

## 十二、测试设计（我来写）

你完成核心代码后，我会补测试：

```text
TaskSubmitIntegrationTest
```

测试思路：

```text
1. 创建 ServiceRegistry / NodeChannelMap / LoadBalancer / TaskStore / TaskScheduler
2. 注册一个 fake Worker
3. 绑定 fake Worker 的 EmbeddedChannel
4. 用 EmbeddedChannel 模拟客户端向 ServerHandler 发送 TASK_SUBMIT
5. 验证 Worker 收到了 TASK_SUBMIT
6. 验证没有 Worker 时客户端收到 TASK_RESULT(FAILED)
```

如果 EmbeddedChannel 对多 Channel 转发不好测，我会拆成更小的 ServerHandler 单元测试。

---

## 十三、本课完成标准

- `MessageType` 增加 `TASK_SUBMIT` / `TASK_RESULT`
- YAML 增加新消息类型序列化路由
- `Message` 增加 createTaskSubmit / createTaskResult
- `ServerHandler` 能处理 `TASK_SUBMIT`
- 调度失败能直接返回 `TASK_RESULT`
- 调度成功能把 `TASK_SUBMIT` 转发给目标 Worker
- 现有 RPC 的 `TASK_REQUEST` / `TASK_RESPONSE` 不受影响


# RPC 原理与异步响应

> 前置阅读，搞懂 RPC 是什么、requestId + CompletableFuture 怎么工作

---

## 一、问题场景：从已有代码出发

你第 2 课的 `ClientHandler` 是这样发消息的：

```java
// ClientHandler.java — channelActive
ctx.writeAndFlush(Message.createTaskRequest("你好，服务器！"));
```

然后 `ServerHandler` 收到后回复：

```java
// ServerHandler.java — channelRead0
Message reply = Message.createTaskResponse(message.getRequestId(), "成功收到消息");
ctx.writeAndFlush(reply);
```

**这已经是一次最简单的「请求→响应」了！** 但有一个问题：

`ClientHandler` 发请求和收响应是**分离的**——发请求在 `channelActive`，收响应在 `channelRead0`。如果我想发一个请求然后「等待」它的响应，像这样：

```java
// 我希望的写法（同步调用）
String result = controller.echo("你好");  // 发请求，等响应，拿到结果
System.out.println(result);               // 拿到结果后再执行下一行
```

目前做不到。因为 Netty 是异步的——你发消息出去，不能阻塞线程等回复（会卡住 EventLoop）。

**RPC 就是解决这个问题的：让异步通信看起来像同步调用。**

---

## 二、生活中的类比：餐厅点餐机

你去快餐店点餐：

```
方式一（异步回调，当前做法）：
  你下单 → 拿走蜂鸣器 → 去座位等着 → 蜂鸣器响了 → 回去取餐
  问题：你没法「下单后直接等着拿到餐再走」

方式二（同步，你想要的）：
  你下单 → 服务员端出来给你 → 你拿到餐走人
  问题：服务员在你面前等着，期间没法服务别人（阻塞线程）

方式三（RPC，两全其美）：
  你下单，收银员给你一个小票（上面有订单号 #42）
  你拿着票等着，中间可以刷手机（不阻塞）
  后厨喊 "#42 好了！" → 你去拿餐 → 配对成功
```

**核心机制：订单号 + 通知。** 你发请求时带上唯一编号（requestId），收到响应时按编号找回原始请求。你的线程在等响应期间可以做别的事。

---

## 三、RPC 的 4 个角色

```
  ┌─────────┐                    ┌─────────┐
  │ Caller  │────请求(requestId)────→│  Callee │
  │ (调用方) │←──响应(requestId)────│ (被调方) │
  └─────────┘                    └─────────┘
```

在你的项目中：

```
  ┌──────────────┐                    ┌──────────────┐
  │ WorkerClient │──"请帮我做任务"──→│  Controller   │
  │  (调用方)     │←──"任务结果"─────│  (被调方)      │
  └──────────────┘                    └──────────────┘
```

Worker 是调用方（caller），Controller 是被调方（callee）。Worker 发一个请求，拿到一个 `CompletableFuture`，等 Controller 的响应到了，这个 Future 就完成。

---

## 四、CompletableFuture：一个「以后会有结果」的容器

### 4.1 普通方法调用 vs Future

```java
// 普通调用：调用线程阻塞等结果
String result = slowFunction();  // 线程卡在这里 5 秒
System.out.println(result);      // 5 秒后才执行这行

// Future：立即返回一个「占位符」，线程不阻塞
CompletableFuture<String> future = asyncFunction();  // 立即返回！
System.out.println("我先干别的事...");                 // 立即执行
String result = future.get();  // 真正需要结果时才等待（也可以设超时）
```

### 4.2 核心操作

```java
CompletableFuture<String> future = new CompletableFuture<>();

// 生产者：数据到了就「完成」这个 Future
future.complete("嗨，数据到了！");

// 消费者A：阻塞等待
String result = future.get();                // → "嗨，数据到了！"

// 消费者B：非阻塞回调（推荐！不卡线程）
future.thenAccept(result -> {
    System.out.println("收到: " + result);    // 数据到了自动执行
});

// 消费者C：设超时
String result = future.get(5, TimeUnit.SECONDS);  // 最多等 5 秒
```

**关键理解：`CompletableFuture` 是「生产者-消费者」的桥梁。**
- 一端（网络线程）收到响应后调用 `future.complete(响应数据)`
- 另一端（你的业务代码）通过 `future.get()` 或 `future.thenAccept()` 拿到结果

---

## 五、待响应池：核心数据结构的来龙去脉

### 5.1 问题

Worker 可能同时发出多个请求：

```
Worker 发请求 #1 (requestId=aaa) → 等待响应 #1
Worker 发请求 #2 (requestId=bbb) → 等待响应 #2
Worker 发请求 #3 (requestId=ccc) → 等待响应 #3

Controller 的响应 #2 回来了（requestId=bbb）
  → 怎么找到是哪个请求的等待者？
```

### 5.2 解决方案

用一个 Map 存储所有「待响应」的请求：

```java
//                    requestId         这个请求对应的 Future
ConcurrentHashMap<String, CompletableFuture<Message>> pendingRequests
```

工作流程：

```
发请求时：
  1. 生成 requestId
  2. 创建 CompletableFuture
  3. 放入 Map: pendingRequests.put(requestId, future)
  4. 发送请求消息（带上 requestId）
  5. 返回 future 给调用方

收响应时：
  1. 从响应消息中取出 requestId
  2. 从 Map 中取出对应的 CompletableFuture: future = pendingRequests.remove(requestId)
  3. future.complete(响应消息) → 通知调用方「结果到了！」
```

### 5.3 一张图看清全流程

```
时间线 →

Worker 业务线程                    Worker Netty 线程           Controller
      │                                │                       │
      │ ① call("echo", "hello")       │                       │
      │    生成 requestId = "xyz"     │                       │
      │    创建 future = new CF<>()    │                       │
      │    pending.put("xyz", future)  │                       │
      │    写请求消息 ──────────────→  │                       │
      │    返回 future 给调用方        │  ② 网络发送 ──────────→│
      │                                │                       │
      │ ③ 调用方拿到 future，          │                       │ ④ 处理请求
      │    可以 thenAccept(结果→...)   │                       │    生成响应 msg
      │    不阻塞，继续干别的事         │  ⑤ 网络收到响应 ←─────│    requestId = "xyz"
      │                                │                       │
      │                                │ ⑥ pending.remove("xyz")
      │                                │    future.complete(响应)
      │                                │                       │
      │ ⑦ future 完成！                │                       │
      │    thenAccept 的回调触发        │                       │
      │    System.out.println(结果)     │                       │
      ↓                                ↓                       ↓
```

---

## 六、你要写的 RpcClient

### 6.1 它解决什么问题？

封装上面那整套「发请求→等响应→匹配 requestId」的流程，让调用方只需要：

```java
RpcClient rpc = new RpcClient(channel);
rpc.call("echo", "hello").thenAccept(result -> {
    System.out.println("Controller 回复: " + result);
});
```

一行 `call()`，背后自动完成：生成 requestId → 创建 Future → 放入待响应池 → 发消息 → 收到响应时匹配 → complete Future。

### 6.2 核心方法设计

```java
public class RpcClient {

    // 待响应池：requestId → CompletableFuture
    private final ConcurrentHashMap<String, CompletableFuture<Message>> pendingRequests
        = new ConcurrentHashMap<>();

    private final Channel channel;

    public RpcClient(Channel channel) {
        this.channel = channel;
    }

    /**
     * 发送 RPC 请求，返回一个 Future
     * @param method  方法名（如 "echo"）
     * @param body    请求体
     * @return CompletableFuture，Controller 回复后自动完成
     */
    public CompletableFuture<Message> call(String method, String body) {
        // 1. 创建请求消息
        Message request = Message.createTaskRequest(body);

        // 2. 创建 Future 并注册到待响应池
        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(request.getRequestId(), future);

        // 3. 设置超时（可选，防止内存泄漏）
        // future.orTimeout(30, TimeUnit.SECONDS);

        // 4. 发出去
        channel.writeAndFlush(request);

        // 5. 返回 Future 给调用方
        return future;
    }

    /**
     * 收到响应消息时调用（由 ClientHandler 触发）
     */
    public void onResponse(Message response) {
        CompletableFuture<Message> future = pendingRequests.remove(response.getRequestId());
        if (future != null) {
            future.complete(response);
        }
    }
}
```

### 6.3 位置

`RpcClient` 放在 `common` 模块的 `transport` 包——它是网络传输层的能力，Worker 和 Controller 都能用（虽然当前只有 Worker 调 Controller，但设计上应该通用）。

---

## 七、为什么用 CompletableFuture 而不是直接阻塞？

| 方案 | 优点 | 缺点 |
|---|---|---|
| ① `synchronized` + `wait/notify` | 老派熟悉 | 容易死锁、代码啰嗦 |
| ② `CountDownLatch` | 简单 | 只能用一次，不灵活 |
| ③ **`CompletableFuture`** | 不阻塞线程、支持超时、链式回调 | Java 8+ 才有的概念 |

在 Netty 中，**绝不能阻塞 EventLoop 线程**。如果你在 `channelRead` 里调 `future.get()`，整个 EventLoop 卡住了，其他所有连接都得不到处理。`CompletableFuture.thenAccept()` 是非阻塞的——注册一个回调，数据到了自动执行。

---

## 八、异常处理 + 超时机制

```java
// 超时保护：防止 Future 永远不完成（内存泄漏）
future.orTimeout(30, TimeUnit.SECONDS)
      .exceptionally(ex -> {
          // 超时或网络异常时触发
          System.err.println("RPC 调用失败: " + ex.getMessage());
          return null;
      });

// 或者更细致：
future.whenComplete((result, error) -> {
    if (error != null) {
        System.err.println("出错了: " + error.getMessage());
    } else {
        System.out.println("成功: " + result.getBody());
    }
});
```

---

## 九、改造后的 Pipeline

### Worker 端（Client）— 新增 RpcClient 持有者

```java
// ClientHandler 改造成「分发器」
public class ClientHandler extends SimpleChannelInboundHandler<Message> {
    private final RpcClient rpcClient;  // 持有 RpcClient 引用

    public ClientHandler(RpcClient rpcClient) {
        this.rpcClient = rpcClient;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
        if (msg.getType() == MessageType.TASK_RESPONSE) {
            // 收到响应 → 交给 RpcClient 匹配 requestId
            rpcClient.onResponse(msg);
        }
    }
}
```

### Controller 端（Server）— 不用改

Controller 的 ServerHandler 还是跟以前一样：收到请求 → 处理 → 回复。只不过回复时必须**带上原始的 requestId**，这样 Worker 端才能匹配。

```java
// ServerHandler — 注意 requestId 必须保持和请求一致
Message reply = Message.createTaskResponse(
    message.getRequestId(),   // ← 这个必须和请求相同！
    "处理结果"
);
ctx.writeAndFlush(reply);
```

---

## 十、需要创建/修改的文件

| 文件 | 操作 | 谁写 |
|------|------|------|
| `common/.../transport/RpcClient.java` | **新建** | **你写** |
| `worker/.../ClientHandler.java` | **修改** — 持有 RpcClient，收到响应时分发 | **你写** |
| `worker/.../WorkerClient.java` | **修改** — 创建 RpcClient 并传给 ClientHandler | **你写** |

无需修改的文件（已经做对了）：
- `ServerHandler.java`：回复时已带 `message.getRequestId()` ✅
- `Message.java`：`createTaskRequest` 已自动生成 requestId ✅

---

## 十一、学完本文应该能回答的问题

1. RPC 解决什么问题？（让异步通信看起来像同步调用）
2. `requestId` 在整个流程中起什么作用？（匹配请求和响应的唯一标识）
3. `CompletableFuture` 是什么？它和普通方法返回值有什么区别？
4. `ConcurrentHashMap<String, CompletableFuture<Message>>` 这个数据结构为什么存在？
5. 为什么在 Netty 中不能阻塞等响应，必须用异步回调？
6. 发请求时做了什么？收响应时做了什么？画流程图。
7. 如果 Controller 崩了没回响应，`pendingRequests` 里的 Future 会怎样？（内存泄漏 → 需要超时保护）
8. `future.complete()` 和 `future.get()` 分别在哪一端调用？
9. Controller 端回复时最重要的原则是什么？（requestId 必须和请求一致）
10. RpcClient 为什么放在 common 模块而不是 worker 模块？

---

---

## 十二、推荐阅读（由浅入深）

### 第一阶段：理解 CompletableFuture（先看这个）

- [CompletableFuture 原理及应用场景详解](https://developer.aliyun.com/article/1659224) — 从零讲起，代码示例很多
- [CompletableFuture：Java 异步编程的终极武器](https://cloud.tencent.cn/developer/article/2576450) — 比较全面，覆盖 allOf / anyOf / 超时
- [CompletableFuture 快速入门教程](https://cloud.tencent.cn/developer/article/2511334) — 短小精悍，适合快速过一遍

### 第二阶段：理解 RPC 核心机制（再看这个）

- [手写 RPC-01：框架设计与核心概念](https://zpj80231.github.io/znote/views/source/code/rpc/rpc-source-01.html) — 从 0 讲 RPC 有哪些模块，很清晰
- [异步 RPC：压榨单机吞吐量](https://developer.aliyun.com/article/1694164) — 讲清楚「同步 vs 异步 vs 全异步」的区别
- [SOFARPC 同步异步实现剖析](https://liangyuanpeng.com/post/sofarpc-sync-async/) — 真实框架的实现，进阶阅读

### 第三阶段：手写 RPC 实战（深入时看）

- [架构思维：动态代理 + 全异步 + 同步语义包装](https://artisan.blog.csdn.net/article/details/151894510) — Dubbo / gRPC 都在用的通用范式
- [服务消费者异步转同步的 Future 实现](https://binghe.gitcode.host/md/middleware/rpc/2022-10-10-%e3%80%8aRPC%e6%89%8b%e6%92%b8%e4%b8%93%e6%a0%8f%e3%80%8b%e7%ac%ac14%e7%ab%a0-%e6%9c%8d%e5%8a%a1%e6%b6%88%e8%b4%b9%e8%80%85%e5%bc%82%e6%ad%a5%e8%bd%ac%e5%90%8c%e6%ad%a5%e7%9a%84%e8%87%aa%e5%ae%9a%e4%b9%89Future%e4%b8%8eAQS%e5%ae%9e%e7%8e%b0.html) — 冰河的 RPC 手撸专栏，很详细

---

看完本文，试着回答上面 10 个问题。能说清楚，就可以开始写 `RpcClient.java` 和改造 `ClientHandler.java`、`WorkerClient.java` 了。

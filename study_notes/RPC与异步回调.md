# RPC 与异步回调

> requestId + CompletableFuture：让异步网络通信看起来像同步调用

---

## 一、RPC 解决什么问题？

### 问题场景

Worker 想调 Controller 的 `echo("你好")` 拿返回值，但 TCP 是异步的——发消息和收响应之间有时间差。你不能阻塞线程等（会卡死 EventLoop）。

### 解决方案

用 `requestId` 连接「发出去的请求」和「收回来的响应」：

```
Worker → 请求(requestId="abc") → Controller
Worker ← 响应(requestId="abc") ← Controller
```

匹配上了！调用方拿到了正确的返回值。

---

## 二、核心数据结构

```java
// 待响应池：「取餐登记本」
ConcurrentHashMap<String, CompletableFuture<Message>> pendingRequests;
//                   ↑                          ↑
//              requestId                    等这个请求的 Future
```

**发请求时：** 把 requestId → 空盒子的映射存进去
**收响应时：** 按 requestId 取出盒子，把结果塞进去
**同时操作：** 两个线程可能同时 put 和 remove → 必须用 ConcurrentHashMap

---

## 三、完整调用链路

```
① 业务代码：rpcClient.call("你好")
      ↓
② call() 内部：
   · Message.createTaskRequest("你好")  → 消息 + 自动生成 requestId
   · new CompletableFuture<>()          → 空盒子
   · pendingRequests.put(requestId, 盒子) → 登记
   · channel.writeAndFlush(消息)        → 异步发出
   · return 盒子                        → 立刻返回
      ↓
③ 业务代码：盒子.thenAccept(响应 → print(响应))
   注册回调，不阻塞，继续干别的
      ↓
④ Controller 处理 → 回复 TASK_RESPONSE(requestId 相同)
      ↓
⑤ Netty 收到响应 → ClientHandler.channelRead0()
   → rpcClient.onResponse(msg)
   → pendingRequests.remove(requestId) → 捞出盒子
   → future.complete(响应)             → 盒子满了！
      ↓
⑥ 回调触发！thenAccept 里的代码自动执行
```

---

## 四、关键点

### 为什么用 CompletableFuture 而不是阻塞等？

| 做法 | 后果 |
|---|---|
| `Thread.sleep()` 等 | EventLoop 卡住，其他所有连接瘫痪 |
| `CompletableFuture.thenAccept()` | 注册回调，不阻塞，数据到了自动触发 |

**在 Netty 里绝对不能阻塞 EventLoop 线程。**

### 为什么 requestId 必须严格匹配？

Controller 回复时如果不带原始的 requestId，Worker 端找不到对应的盒子，结果就丢了：

```java
// Controller：正确的做法
Message.createTaskResponse(
    message.getRequestId(),   // ← 必须和请求的 requestId 一模一样
    "处理结果"
);
```

### 乱序如何处理？

多个请求并发发出，响应可能乱序到达。每个请求有独立的 requestId → 独立的盒子 → 独立匹配。

```java
// 请求 A、B、C 发出去
// 响应可能是 C、A、B 的顺序回来
// 每个都能正确匹配，互不干扰
```

---

## 五、RpcClient 代码结构

```java
public class RpcClient {
    private final ConcurrentHashMap<String, CompletableFuture<Message>> pendingRequests;
    private final Channel channel;

    public CompletableFuture<Message> call(String body) {
        Message request = Message.createTaskRequest(body);
        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(request.getRequestId(), future);
        channel.writeAndFlush(request);
        return future;
    }

    public void onResponse(Message response) {
        CompletableFuture<Message> future = pendingRequests.remove(response.getRequestId());
        if (future != null) {
            future.complete(response);
        }
    }
}
```

---

## 六、面试问答

**Q: RPC 的核心机制是什么？**
A: requestId + 待响应池（ConcurrentHashMap）+ CompletableFuture。发请求时用 requestId 登记一个空 Future，收响应时按 requestId 找到它并 complete。调用方拿到 Future 不阻塞，注册回调等通知。

**Q: 为什么用 ConcurrentHashMap？**
A: 业务线程 put，Netty 线程 remove，两个线程同时操作同一个 Map，必须线程安全。

**Q: 多请求并发会不会混乱？**
A: 不会。每个请求有独立的 requestId → 独立的 Future，响应按 requestId 对号入座，乱序返回也没问题。

**Q: 和 Dubbo、gRPC 有什么区别？**
A: 核心机制（requestId + Future + pendingRequests）一模一样。Dubbo 多了动态代理、服务发现、负载均衡、熔断等，但通信层内核就是你现在写的这个东西。

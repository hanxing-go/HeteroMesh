# 第 9 课：RpcRequest/RpcResponse + 超时机制

---

## 上下文（给新会话看）

- **项目**：HeteroMesh，从零手写分布式 RPC 框架，Java 21 + Netty + Maven 多模块
- **路径**：`C:\Users\12099\Desktop\HeteroMesh\HeteroMesh`
- **已完成**：第 8 课（动态代理 + 服务发布/引用），Client 通过 JDK 代理透明调用远程方法，Worker 通过 RpcDispatcher 反射执行并返回
- **当前状态**：RPC 调用链路已经通了（Client 代理 → Controller 转发 → Worker 反射 → 返回），但整个请求/响应模型是「裸奔」的——RpcInvocation 直接塞进 body，返回时成功和失败都用同一个 String，错误信息是手动拼的 JSON，超时写死在代码里

---

## 一、这节课解决什么问题？

### 1.1 当前状态：三个「将就」的地方

回顾第 8 课做完后的代码，有三个地方是靠「约定」和「凑合」撑着的：

**痛点 ①：请求和调用信息混在一起**

```java
// RpcProxy.java 当前代码
RpcInvocation invocation = new RpcInvocation();
invocation.setServiceName(interfaceClass.getName());
invocation.setMethodName(method.getName());
invocation.setParameterTypes(getParameterTypeNames(method));
invocation.setArgs(args != null ? args : new Object[0]);
invocation.setOneWay(false);  // ← oneWay 和 requestId 写死在 RpcInvocation 上
// requestId 呢？根本没设！靠 Message.createTaskRequest() 内部生成的。
```

**问题**：`RpcInvocation` 身兼两职——既是「方法调用的描述」（serviceName/methodName/args），又要管「RPC 传输的元信息」（requestId/timeout/oneWay）。当你以后想做「同一个 RpcInvocation 重试 3 次」，requestId 应该每次不同，但 invocation 对象只有一个。**职责不分离，扩展就困难。**

**痛点 ②：Worker 端错误是手动拼的 JSON 字符串**

```java
// ClientHandler.java 当前代码
} catch (Exception e) {
    log.error("RPC 调用失败: requestId = {}", msg.getRequestId(), e);
    String errorBody = "{\"error\":\"" + e.getMessage() + "\"}";  // ← 手拼 JSON！
    Message response = Message.createTaskResponse(msg.getRequestId(), errorBody);
    ctx.writeAndFlush(response);
}
```

**问题**：调用方拿到这个字符串后，要靠 `GSON.fromJson(responseBody, method.getReturnType())` 反序列化——如果 method 返回 `String`，那调用方拿到的就是 `{"error":"..."}` 这个字符串本身，完全不知道这是错误还是正常返回值。

更严重的是：**调用方无法程序化地区分错误类型**。是「服务没找到」？「方法不存在」？「业务逻辑抛异常」？「超时」？——全部混在一起，调用方只能 `if (result.contains("error"))` 这种脆弱的字符串匹配。

**痛点 ③：超时写死在 RpcProxy 里**

```java
// RpcProxy.java 当前代码
private static final long DEFAULT_TIMEOUT_MS = 30_000;  // ← 写死 30 秒
// ...
Message response = future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
```

**问题**：
- 不同接口需要不同超时：查询接口 2 秒就够了，推理接口可能需要 60 秒
- `future.get(timeout)` 超时后直接抛 `TimeoutException`，调用方 try-catch 拿到的异常跟 RPC 框架没有任何结构化信息
- 超时后 pendingRequests 里的 future 永远不会被清理（因为没有对应的 response 来 complete 它），造成内存泄漏

### 1.2 这节课后的效果

```java
// ========== 改前 ==========
// 调用方：
String result = service.processImage("img.jpg");
// → 成功返回字符串，失败也返回字符串（内容是 {"error":"..."}）
// → 超时抛 TimeoutException，调用方自己 catch
// → 无法区分错误类型

// Worker 端：
} catch (Exception e) {
    String errorBody = "{\"error\":\"" + e.getMessage() + "\"}";  // 手拼
    ctx.writeAndFlush(Message.createTaskResponse(requestId, errorBody));
}

// ========== 改后 ==========
// 调用方：
RpcResponse response = service.processImage("img.jpg");
// → response.getStatus() == RpcStatus.SUCCESS  → 调成功，取 response.getResult()
// → response.getStatus() == RpcStatus.TIMEOUT   → 超时了
// → response.getStatus() == RpcStatus.NOT_FOUND → 服务没找到
// → response.getStatus() == RpcStatus.ERROR     → 业务异常，取 response.getErrorMessage()

// Worker 端：
} catch (IllegalArgumentException e) {  // 服务没找到
    RpcResponse resp = RpcResponse.error(RpcStatus.NOT_FOUND, e.getMessage());
    ctx.writeAndFlush(Message.createTaskResponse(requestId, GSON.toJson(resp)));
} catch (Exception e) {  // 业务异常
    RpcResponse resp = RpcResponse.error(RpcStatus.ERROR, e.getMessage());
    ctx.writeAndFlush(Message.createTaskResponse(requestId, GSON.toJson(resp)));
}
```

### 1.3 本质：从「字符串约定」升级到「结构化协议」

```
当前：一切信息靠字符串 + 约定
  body = "你好"              → 这是裸字符串
  body = RpcInvocation JSON  → 这是请求
  body = "处理完成"           → 这是成功返回
  body = {"error":"..."}     → 这是失败返回（手动拼的！）
  ↑ 四种不同语义的东西，全塞在同一个 String 字段里，靠「收到的人自己判断」

改后：请求和响应各自有明确的数据结构
  RpcRequest  → 只描述「一次 RPC 请求」
  RpcResponse → 只描述「一次 RPC 结果」，成功/失败/超时都在里面
  ↑ 双方对数据格式有共识，不再靠猜
```

---

## 二、四个新类的设计

### 2.1 RpcStatus — 响应状态枚举

所有 RPC 调用只会有这几种结果，全部枚举出来：

```
RpcStatus 枚举值：
  SUCCESS(0)           — 调用成功，result 里是返回值
  TIMEOUT(1)           — 调用超时（客户端在指定时间内没等到响应）
  NOT_FOUND(2)         — 服务未找到（Worker 端没有注册这个接口）
  METHOD_NOT_FOUND(3)  — 方法未找到（接口注册了，但没有这个方法）
  ERROR(4)             — 业务方法执行时抛了异常
  FRAMEWORK_ERROR(5)   — 框架内部错误（序列化失败、网络异常等）
```

**对标 Dubbo**：Dubbo 的 `RpcResult` 里有 `Response.OK` / `CLIENT_TIMEOUT` / `SERVER_ERROR` 等状态码，`RpcStatus` 就是 HeteroMesh 的对应实现。

**为什么用枚举而不是 int？**
- 类型安全：`RpcStatus.SUCCESS` 不会跟 `RpcStatus.TIMEOUT` 搞混
- 自文档化：看到 `RpcStatus.NOT_FOUND` 就知道含义，不需要查表 `code=2 是什么意思`
- 序列化友好：可以用 `name()` 序列化，`valueOf()` 反序列化

### 2.2 RpcRequest — 请求封装

**为什么要从 RpcInvocation 里拆出来？**

第 8 课的 `RpcInvocation` 是一个「方法调用描述符」，它天然适合在 Worker 端被 RpcDispatcher 消费。但从 Client 端看，一次 RPC 调用除了「调哪个方法」还需要「等多久」「要不要回执」「用什么 requestId 追踪」。这些是 **RPC 传输层的关注点**，不应该污染 `RpcInvocation`。

```
RpcRequest (传输层)           RpcInvocation (应用层)
─────────────────────        ─────────────────────
requestId       ← 追踪一次调用    serviceName     ← 调哪个接口
timeout         ← 等多久         methodName      ← 调哪个方法
oneWay          ← 要不要回复     parameterTypes  ← 方法签名
invocation      ← 指向具体的调用内容  args        ← 实际参数
```

**好处**：以后做重试（第 11 课），每次重试生成新的 `RpcRequest`（带新的 `requestId`），但共享同一个 `RpcInvocation`（方法调用内容不变）。

```java
// 第一次调用
RpcRequest req1 = new RpcRequest(invocation, 3000);  // requestId = "aaa-001"
// 超时后重试
RpcRequest req2 = new RpcRequest(invocation, 3000);  // requestId = "bbb-002"（新 ID）
// ↑ invocation 是同一个对象，但 requestId 不同 —— 完美对应「同一次业务操作的重试」
```

### 2.3 RpcResponse — 响应封装

**为什么要从 String 升级？**

第 8 课的返回值是裸 String，成功时 `GSON.toJson(result)`，失败时 `"{\"error\":\"...\"}"`。调用方拿到后已经丢失了「这是成功还是失败」的结构信息。

`RpcResponse` 统一了这个格式：

```json
// 成功
{
  "status": "SUCCESS",
  "result": "图片 /data/images/cat.jpg 处理完成",
  "errorMessage": null
}

// 失败（服务未找到）
{
  "status": "NOT_FOUND",
  "result": null,
  "errorMessage": "服务未找到: com.heteromesh.demo.TaskService"
}

// 失败（业务异常）
{
  "status": "ERROR",
  "result": null,
  "errorMessage": "java.lang.RuntimeException: 磁盘空间不足"
}

// 超时
{
  "status": "TIMEOUT",
  "result": null,
  "errorMessage": "调用超时，等待 5000ms 未收到响应"
}
```

**关键设计**：`result` 字段类型是 `Object`（存方法返回值），`errorMessage` 是 `String`。成功时取 `result`，失败时取 `errorMessage`——**调用方永远先看 `status`，再决定取哪个字段**。

### 2.4 RpcFuture — 超时控制的 Future 包装

**为什么要包装 CompletableFuture？**

当前 RpcProxy 里直接用 `future.get(30_000, MILLISECONDS)`，问题是：

1. **超时不可配置**：调用方没法说「这个接口等 2 秒就够了」
2. **超时后没有清理**：`future.get()` 抛 `TimeoutException` 后，pendingRequests 里的 entry 还在，永远没人移除 → 内存泄漏
3. **异常类型不统一**：成功的返回是 `Message`，超时抛 `TimeoutException`，调用方要用完全不同的方式处理

`RpcFuture` 做的事情：

```
CompletableFuture<Message> future = rpcClient.call(body);

// 改前：直接用 CompletableFuture
Message response = future.get(30000, MILLISECONDS);  // 超时抛异常

// 改后：用 RpcFuture 包装
RpcFuture rpcFuture = new RpcFuture(future, requestId, rpcClient);
RpcResponse response = rpcFuture.get(3000);  // 超时 3 秒
// → 超时不抛异常，返回 RpcResponse(status=TIMEOUT)
// → 同时自动清理 pendingRequests 里的残留 entry
```

**RpcFuture 的核心逻辑**：

```java
public RpcResponse get(long timeoutMs) {
    try {
        Message msg = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        // 正常拿到响应 → 解析 RpcResponse
        return GSON.fromJson(msg.getBody(), RpcResponse.class);
    } catch (TimeoutException e) {
        // 超时 → 清理 pending + 返回 TIMEOUT 响应
        rpcClient.cleanup(requestId);
        return RpcResponse.timeout("调用超时，等待 " + timeoutMs + "ms 未收到响应");
    }
}
```

---

## 三、调用链路对比（改前 vs 改后）

### 3.1 改前（第 8 课）

```
Client (RpcProxy)                  Controller              Worker (ClientHandler)
─────────────────                  ──────────              ──────────────────────
① 构造 RpcInvocation
② GSON.toJson(invocation)
③ rpcClient.call(body)
   → Message body = invocationJson
                                 → 转发 TASK_REQUEST     → 收到 TASK_REQUEST
                                 (不读 body)             ④ GSON.fromJson(body, RpcInvocation)
                                                         ⑤ dispatcher.dispatch(json)
                                                            → 找到服务
                                                            → 反射调用
                                                            → return result
                                                         ⑥ 成功: body = GSON.toJson(result)
                                                            失败: body = {"error":"..."}  ← 手动拼！
                                 ← 转发 TASK_RESPONSE    ← ctx.writeAndFlush(response)
⑧ future.get(30s, MILLISECONDS)
⑨ GSON.fromJson(body, 返回类型)
   → 成功: 拿到返回值
   → 失败: 拿到错误字符串（无法区分是否成功）
   → 超时: TimeoutException（内存泄漏！）
```

### 3.2 改后（第 9 课）

```
Client (RpcProxy)                  Controller              Worker (ClientHandler)
─────────────────                  ──────────              ──────────────────────
① 构造 RpcInvocation
② 构造 RpcRequest
   (invocation + timeout + oneWay + requestId)
③ GSON.toJson(request)
④ rpcClient.call(body)
   → Message body = RpcRequest JSON
                                 → 转发 TASK_REQUEST     → 收到 TASK_REQUEST
                                 (不读 body)             ⑤ GSON.fromJson(body, RpcRequest)
                                                         ⑥ 取出 request.invocation
                                                         ⑦ dispatcher.dispatch(invocation)
                                                            → 找到服务
                                                            → 反射调用
                                                            → return result
                                                         ⑧ 成功: RpcResponse.success(result)
                                                            服务不存在: RpcResponse.error(NOT_FOUND, msg)
                                                            方法异常: RpcResponse.error(ERROR, msg)
                                                         ⑨ ctx.writeAndFlush(
                                                              TASK_RESPONSE[RpcResponse JSON])
                                 ← 转发 TASK_RESPONSE    ←
⑩ RpcFuture.get(timeout)
   → 正常: GSON.fromJson(body, RpcResponse.class)
   → 超时: RpcResponse(status=TIMEOUT) + 清理 pending
⑪ 拿到 RpcResponse
   → response.isSuccess() → 取 response.getResult()
   → !response.isSuccess() → 取 response.getStatus() + getErrorMessage()
```

**核心变化总结**：

| 维度 | 改前 | 改后 |
|------|------|------|
| 请求格式 | `RpcInvocation` 直接序列化 | `RpcRequest` 包装（含 timeout/requestId）+ 内部 `RpcInvocation` |
| 响应格式 | 裸 String（成功）/ 手动 JSON（失败） | 统一 `RpcResponse`（status + result + errorMessage） |
| 错误区分 | 无法区分，靠字符串匹配 | `RpcStatus` 枚举，程序化判断 |
| 超时处理 | `future.get(30000)` 抛异常 + 内存泄漏 | `RpcFuture.get(timeout)` 返回 TIMEOUT + 清理 pending |
| 超时配置 | 写死 30 秒 | 调用方可配置，每次调用独立设置 |
| Controller | 不关心，透明转发 | **仍然不关心，透明转发！** |

---

## 四、你要写的核心代码

### 4.1 RpcStatus（~20 行）

位置：`heteromesh-common/src/main/java/com/heteromesh/rpc/RpcStatus.java`

**为什么需要这个类？**
- 统一 RPC 调用的所有可能结果状态，让 Client 和 Worker 对「成功/失败/超时/未找到」有共同的语言
- **和原来的区别**：原来没有状态码概念，成功失败靠字符串内容猜测

```java
package com.heteromesh.rpc;

/**
 * RPC 调用响应状态。
 * Client 端拿到 RpcResponse 后先检查 status，再决定如何处理 result/errorMessage。
 */
public enum RpcStatus {

    /** 调用成功 */
    SUCCESS(0),

    /** 调用超时（Client 端在指定时间内未收到响应） */
    TIMEOUT(1),

    /** 服务未找到（Worker 端没有注册对应的接口实现） */
    NOT_FOUND(2),

    /** 方法未找到（服务已注册，但没有匹配的方法） */
    METHOD_NOT_FOUND(3),

    /** 业务方法执行时抛出异常 */
    ERROR(4),

    /** 框架内部错误（序列化/反序列化失败、网络异常等） */
    FRAMEWORK_ERROR(5);

    private final int code;

    RpcStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
```

### 4.2 RpcRequest（~40 行）

位置：`heteromesh-common/src/main/java/com/heteromesh/rpc/RpcRequest.java`

**为什么需要这个类？**
- 把 RPC 传输层的元信息（requestId、timeout、oneWay）从 RpcInvocation 中分离出来
- **和原来的区别**：原来 `requestId`、`oneWay` 混在 `RpcInvocation` 里，现在 `RpcInvocation` 只管「调哪个方法」，`RpcRequest` 负责「怎么发、等多久」

```java
package com.heteromesh.rpc;

import java.util.UUID;

/**
 * 一次 RPC 请求的完整描述，序列化后放在 TASK_REQUEST 的 body 里。
 *
 * RpcRequest = RPC 传输元信息 + RpcInvocation（方法调用内容）
 *
 * 分离的好处：
 * - RpcInvocation 只管「调哪个类的哪个方法」，可在多次重试中复用
 * - RpcRequest 管「这次请求的追踪 ID」「等多久」「要不要回复」
 * - 重试时：同一个 invocation，不同的 requestId
 */
public class RpcRequest {

    /** 请求追踪 ID，每次调用唯一 */
    private String requestId;

    /** 具体的调用信息（服务名 + 方法名 + 参数） */
    private RpcInvocation invocation;

    /** 超时毫秒数（0 表示不限制） */
    private long timeoutMs;

    /** 单向调用：true = 发了不管结果 */
    private boolean oneWay;

    // 构造方法 + getter/setter（你来写）
}
```

**关于 timeout 字段的意义**：
`timeoutMs` 存的是「调用方期望的最大等待时间」。它随 RpcRequest 一起发给 Worker，Worker 可以用它做**调用截止时间检查**（比如请求在路上花了 4 秒，Worker 只剩 1 秒处理，超时就别执行了直接返回 TIMEOUT）。不过第 9 课先不做到这个深度——这节课只需在 Client 端用这个值来做 `RpcFuture.get(timeoutMs)`。

### 4.3 RpcResponse（~50 行）

位置：`heteromesh-common/src/main/java/com/heteromesh/rpc/RpcResponse.java`

**为什么需要这个类？**
- 统一所有 RPC 结果的返回格式，调用方先看 `status`，再决定取 `result` 还是 `errorMessage`
- **和原来的区别**：原来 body 可能是成功返回值、也可能是 `{"error":"..."}`，调用方无法可靠判断

```java
package com.heteromesh.rpc;

/**
 * RPC 调用响应，由 Worker 端构造，序列化后放在 TASK_RESPONSE 的 body 里。
 *
 * 使用约定：
 * 1. Client 端拿到 RpcResponse 后，先检查 isSuccess()
 * 2. isSuccess() == true  → 取 getResult()
 * 3. isSuccess() == false → 取 getStatus() + getErrorMessage()
 */
public class RpcResponse {

    /** 响应状态 */
    private RpcStatus status;

    /** 方法返回值（仅 SUCCESS 时有意义） */
    private Object result;

    /** 错误描述（仅非 SUCCESS 时有意义） */
    private String errorMessage;

    // ===== 静态工厂方法（你来写） =====

    /** 构造一个成功响应 */
    public static RpcResponse success(Object result) { ... }

    /** 构造一个失败响应 */
    public static RpcResponse error(RpcStatus status, String errorMessage) { ... }

    /** 构造一个超时响应 */
    public static RpcResponse timeout(String message) { ... }

    // ===== 便捷判断方法 =====

    public boolean isSuccess() {
        return status == RpcStatus.SUCCESS;
    }

    // getter/setter（你来写）
}
```

**为什么用静态工厂方法而不是直接用构造函数？**

```java
// ❌ 用构造函数：调用方需要知道 RpcStatus 的内部细节
RpcResponse resp = new RpcResponse(RpcStatus.ERROR, null, "出错了");
//                                ↑ 第二个参数是 result，传 null 还是传什么？不够直观

// ✅ 用静态工厂：意图一目了然
RpcResponse resp = RpcResponse.error(RpcStatus.ERROR, "出错了");
//                                ↑ 一看就知道是构造一个错误响应
```

这和 `Message.createTaskRequest()` 的设计哲学一致——工厂方法能表达意图，构造函数只是填充字段。

### 4.4 RpcFuture（~60 行）

位置：`heteromesh-common/src/main/java/com/heteromesh/rpc/RpcFuture.java`

**为什么需要这个类？**
- 封装超时等待 + 超时清理 + 统一返回 RpcResponse
- **和原来的区别**：原来直接用 `CompletableFuture<Message>`，超时抛异常且不清理

```java
package com.heteromesh.rpc;

import com.google.gson.Gson;
import com.heteromesh.protocol.Message;
import com.heteromesh.transport.RpcClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RPC 异步调用的 Future 包装。
 *
 * 职责：
 * 1. 等待响应（可配置超时）
 * 2. 超时时自动返回 RpcResponse(status=TIMEOUT)
 * 3. 超时时自动清理 pendingRequests 残留
 * 4. 正常响应时解析为 RpcResponse
 */
public class RpcFuture {

    private static final Gson GSON = new Gson();

    private final CompletableFuture<Message> future;
    private final String requestId;
    private final RpcClient rpcClient;

    public RpcFuture(CompletableFuture<Message> future, String requestId, RpcClient rpcClient) {
        this.future = future;
        this.requestId = requestId;
        this.rpcClient = rpcClient;
    }

    /**
     * 同步等待响应。
     *
     * @param timeoutMs 超时毫秒数
     * @return 成功时返回 Worker 构造的 RpcResponse；超时时返回 status=TIMEOUT 的 RpcResponse
     */
    public RpcResponse get(long timeoutMs) {
        try {
            Message msg = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            // 正常拿到 Message → 解析 body 为 RpcResponse
            return GSON.fromJson(msg.getBody(), RpcResponse.class);
        } catch (TimeoutException e) {
            // ① 清理 pendingRequests 里的残留（否则这个 entry 永远存在 → 内存泄漏）
            rpcClient.cleanup(requestId);
            // ② 返回结构化的超时响应（调用方不需要 try-catch）
            return RpcResponse.timeout("调用超时，等待 " + timeoutMs + "ms 未收到响应");
        } catch (Exception e) {
            rpcClient.cleanup(requestId);
            return RpcResponse.error(RpcStatus.FRAMEWORK_ERROR, e.getMessage());
        }
    }

    /**
     * 使用默认超时（30 秒）。
     */
    public RpcResponse get() {
        return get(30_000);
    }

    /** 返回原始的 CompletableFuture，供异步调用场景使用 */
    public CompletableFuture<Message> getFuture() {
        return future;
    }
}
```

### 4.5 RpcClient 新增 cleanup 方法

位置：`heteromesh-common/src/main/java/com/heteromesh/transport/RpcClient.java`

**为什么需要改？**
- `RpcFuture` 超时时需要清理 `pendingRequests` 里残留的 entry，不然内存泄漏
- **和原来的区别**：原来没有清理机制，超时后的 pending entry 永远不会被移除

```java
// 在 RpcClient 里新增方法：
/**
 * 清理 pendingRequests 中指定 requestId 的条目。
 * 用于超时或取消场景——不会再有对应的响应回来了，主动移除防止内存泄漏。
 */
public void cleanup(String requestId) {
    pendingRequests.remove(requestId);
}
```

### 4.6 RpcProxy 改造

位置：`heteromesh-common/src/main/java/com/heteromesh/rpc/RpcProxy.java`

**为什么要改？**
- 原来直接构造 `RpcInvocation` 并序列化 → 改为构造 `RpcRequest`（包含 invocation + timeout + oneWay）
- 原来直接用 `future.get(30000)` → 改为用 `RpcFuture` 包装
- **和原来的区别**：请求和响应的处理都结构化、可配置了

**改动要点**（你来实现）：

```java
// RpcInvocationHandler.invoke() 方法的改动：

// ========== 改前 ==========
RpcInvocation invocation = new RpcInvocation();
invocation.setServiceName(interfaceClass.getName());
invocation.setMethodName(method.getName());
invocation.setParameterTypes(getParameterTypeNames(method));
invocation.setArgs(args != null ? args : new Object[0]);

String body = GSON.toJson(invocation);  // ← 直接序列化 RpcInvocation
CompletableFuture<Message> future = rpcClient.call(body);

// void / CompletableFuture 返回处理...

Message response = future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
String responseBody = response.getBody();
if (responseBody == null || responseBody.isEmpty()) {
    return null;
}
return GSON.fromJson(responseBody, method.getReturnType());

// ========== 改后 ==========
// ① 构造 RpcInvocation（只描述方法调用内容）
RpcInvocation invocation = new RpcInvocation();
invocation.setServiceName(interfaceClass.getName());
invocation.setMethodName(method.getName());
invocation.setParameterTypes(getParameterTypeNames(method));
invocation.setArgs(args != null ? args : new Object[0]);

// ② 构造 RpcRequest（包装传输层元信息）
RpcRequest request = new RpcRequest();
request.setInvocation(invocation);
request.setTimeoutMs(timeoutMs);  // ← 可从注解/配置获取，这节课先用默认 30s
request.setOneWay(method.getReturnType() == void.class);

// ③ 发送
String body = GSON.toJson(request);
CompletableFuture<Message> future = rpcClient.call(body);

// ④ void 方法：发了不管
if (request.isOneWay()) {
    return null;
}

// ⑤ 异步方法（CompletableFuture 返回值）
if (method.getReturnType() == CompletableFuture.class) {
    return future.thenApply(msg ->
        GSON.fromJson(msg.getBody(), RpcResponse.class));
}

// ⑥ 同步方法：用 RpcFuture 等待 + 解析
RpcFuture rpcFuture = new RpcFuture(future, request.getRequestId(), rpcClient);
RpcResponse response = rpcFuture.get(request.getTimeoutMs());

if (response.isSuccess()) {
    // 取出 result，反序列化为期望的返回类型
    return GSON.fromJson(GSON.toJson(response.getResult()), method.getReturnType());
} else {
    // 抛出异常让调用方感知（后续课可以做 RpcException）
    throw new RuntimeException("RPC 调用失败: " + response.getStatus()
        + " - " + response.getErrorMessage());
}
```

### 4.7 ClientHandler 改造

位置：`heteromesh-worker/src/main/java/com/heteromesh/worker/ClientHandler.java`

**为什么要改？**
- 原来 `dispatch()` 拿到的是裸 `RpcInvocation` JSON → 现在 body 是 `RpcRequest` JSON，要先解析出 `invocation` 字段
- 原来异常处理是手动拼 `{"error":"..."}` → 改为构造 `RpcResponse.error(...)`
- **和原来的区别**：Worker 端也正式遵循 RpcRequest/RpcResponse 协议

**改动要点**（你来实现）：

```java
// handleTaskRequest() 方法的改动：

// ========== 改前 ==========
private void handleTaskRequest(ChannelHandlerContext ctx, Message msg) {
    try {
        Object result = dispatcher.dispatch(msg.getBody());
        String resultJson = result != null ? new Gson().toJson(result) : "";
        Message response = Message.createTaskResponse(msg.getRequestId(), resultJson);
        ctx.writeAndFlush(response);
    } catch (Exception e) {
        log.error("RPC 调用失败: requestId = {}", msg.getRequestId(), e);
        String errorBody = "{\"error\":\"" + e.getMessage() + "\"}";  // ← 手动拼
        Message response = Message.createTaskResponse(msg.getRequestId(), errorBody);
        ctx.writeAndFlush(response);
    }
}

// ========== 改后 ==========
private void handleTaskRequest(ChannelHandlerContext ctx, Message msg) {
    try {
        // ① 解析 RpcRequest（body 不再是裸 RpcInvocation）
        RpcRequest request = GSON.fromJson(msg.getBody(), RpcRequest.class);

        // ② 分发调用（dispatcher 内部会解析 RpcInvocation）
        Object result = dispatcher.dispatch(GSON.toJson(request.getInvocation()));

        // ③ 构造成功响应
        RpcResponse rpcResponse = RpcResponse.success(result);
        String responseJson = GSON.toJson(rpcResponse);
        Message response = Message.createTaskResponse(msg.getRequestId(), responseJson);
        ctx.writeAndFlush(response);

    } catch (IllegalArgumentException e) {
        // 服务未找到 / 方法未找到
        RpcStatus status = e.getMessage().contains("服务未找到")
                ? RpcStatus.NOT_FOUND : RpcStatus.METHOD_NOT_FOUND;
        RpcResponse rpcResponse = RpcResponse.error(status, e.getMessage());
        Message response = Message.createTaskResponse(msg.getRequestId(), GSON.toJson(rpcResponse));
        ctx.writeAndFlush(response);

    } catch (Exception e) {
        log.error("RPC 调用失败: requestId = {}", msg.getRequestId(), e);
        RpcResponse rpcResponse = RpcResponse.error(RpcStatus.ERROR, e.getMessage());
        Message response = Message.createTaskResponse(msg.getRequestId(), GSON.toJson(rpcResponse));
        ctx.writeAndFlush(response);
    }
}
```

### 4.8 RpcDispatcher 可能需要微调

`RpcDispatcher.dispatch()` 原来的参数是 `String invocationJson`（RpcInvocation 的 JSON）。如果 ClientHandler 在调用前已经手动解析了 RpcRequest 并取出了 invocation，那 dispatch 保持不变就行。

具体来说：ClientHandler 负责「解析 RpcRequest → 取出 RpcInvocation → 转成 JSON → 传给 RpcDispatcher」。RpcDispatcher 只要 `RpcInvocation` 的 JSON，不关心外面有没有 RpcRequest 这层包装。这样 RpcDispatcher 保持简单，不承担协议解析职责。

---

## 五、文件清单

### 新建文件（你来写核心逻辑）

| 文件 | 位置 | 说明 |
|------|------|------|
| `RpcStatus.java` | `heteromesh-common/.../rpc/` | 响应状态枚举（6 个状态码） |
| `RpcRequest.java` | `heteromesh-common/.../rpc/` | 请求封装（requestId + invocation + timeoutMs + oneWay） |
| `RpcResponse.java` | `heteromesh-common/.../rpc/` | 响应封装（status + result + errorMessage）+ 静态工厂方法 |
| `RpcFuture.java` | `heteromesh-common/.../rpc/` | Future 包装（超时控制 + 清理 + 统一返回 RpcResponse） |

### 修改文件（你来改）

| 文件 | 改什么 |
|------|--------|
| `RpcProxy.java` | 构造 RpcRequest 替代裸 RpcInvocation；用 RpcFuture 替代裸 future.get()；根据 RpcResponse.isSuccess() 分别处理 |
| `ClientHandler.java` | handleTaskRequest 解析 RpcRequest；异常分类型构造 RpcResponse |
| `RpcClient.java` | 新增 `cleanup(requestId)` 方法 |

### 测试文件（我来写）

| 文件 | 测试什么 |
|------|---------|
| `rpc/RpcStatusTest.java` | 枚举值正确性、getCode() |
| `rpc/RpcResponseTest.java` | success()/error()/timeout() 工厂方法、isSuccess()、JSON 序列化往返 |
| `rpc/RpcRequestTest.java` | requestId 自动生成、与 RpcInvocation 的嵌套序列化/反序列化 |
| `rpc/RpcFutureTest.java` | 正常响应、超时清理、异常处理 |
| `worker/RpcIntegrationTest.java` | **修改已有测试**：适配 RpcRequest/RpcResponse 新协议 |

---

## 六、常见错误预警

### 错误 1：RpcRequest 忘了设 requestId

```java
// ❌ RpcProxy 里构造 RpcRequest 时忘设 requestId
RpcRequest request = new RpcRequest();
request.setInvocation(invocation);
// requestId = null → RpcFuture 用 null 去清理 pendingRequests，清理不掉！

// ✅ 在 RpcRequest 构造函数里自动生成，或者在 RpcProxy 里手动设
RpcRequest request = new RpcRequest();
request.setRequestId(UUID.randomUUID().toString());  // 或者构造函数自动生成
```

**建议**：让 `RpcRequest` 的无参构造函数自动生成 `requestId`，这样即使忘记设也不会丢。

### 错误 2：Gson 反序列化 RpcResponse 时 result 类型丢失

```java
// ❌ 直接取 result 然后强转
RpcResponse resp = GSON.fromJson(body, RpcResponse.class);
String value = (String) resp.getResult();
// → 可能报 ClassCastException！因为 Gson 把 result 里的 JSON 对象解析成了
//   LinkedTreeMap（JSON 对象）或 Double（数字）

// ✅ 先 toJson 再 fromJson，让 Gson 按目标类型重新解析
RpcResponse resp = GSON.fromJson(body, RpcResponse.class);
if (resp.isSuccess()) {
    // resp.getResult() 是 Gson 的原始类型（String/Double/LinkedTreeMap）
    // 转回 JSON 字符串，再按 method.getReturnType() 反序列化
    return GSON.fromJson(GSON.toJson(resp.getResult()), method.getReturnType());
}
```

**这和 RpcProxy 当前的处理方式一样**——`GSON.fromJson(responseBody, method.getReturnType())` 变成 `GSON.fromJson(GSON.toJson(response.getResult()), method.getReturnType())`，多了一次序列化/反序列化往返，但保证了类型正确。

### 错误 3：超时后没清理 pendingRequests

```java
// ❌ 直接 future.get(timeout) — 超时抛异常，pendingRequests 残留
try {
    Message msg = future.get(5000, TimeUnit.MILLISECONDS);
    return GSON.fromJson(msg.getBody(), RpcResponse.class);
} catch (TimeoutException e) {
    return RpcResponse.timeout("超时了");
    // ← 忘了清理！pendingRequests 里的 requestId → future 永远存在
}

// ✅ 在 catch 块里主动清理
} catch (TimeoutException e) {
    rpcClient.cleanup(requestId);     // ← 从 pendingRequests 中移除
    return RpcResponse.timeout("超时了");
}
```

**验证方法**：写测试时，连续发起 100 次请求全部超时，然后检查 `pendingRequests.size()` 是否为 0。不为 0 说明有内存泄漏。

### 错误 4：Worker 端所有异常都 catch 成一个状态

```java
// ❌ 所有异常都返回 ERROR，丢失了「服务未找到」的区分
} catch (Exception e) {
    RpcResponse resp = RpcResponse.error(RpcStatus.ERROR, e.getMessage());
    // 如果异常是「服务未找到」，Client 无法区分
}

// ✅ 按异常类型细分状态
} catch (IllegalArgumentException e) {
    // 服务未找到 / 方法未找到 → NOT_FOUND / METHOD_NOT_FOUND
    RpcStatus st = e.getMessage().contains("服务未找到")
        ? RpcStatus.NOT_FOUND : RpcStatus.METHOD_NOT_FOUND;
    return RpcResponse.error(st, e.getMessage());
} catch (Exception e) {
    // 业务异常 → ERROR
    return RpcResponse.error(RpcStatus.ERROR, e.getMessage());
}
```

### 错误 5：RpcResponse.success(null) vs 空字符串

```java
// ❌ 区分不了「方法返回 null」和「方法没返回值」
RpcResponse.success(null);
// Client 端：result 是 null，是方法真返回了 null 还是出了 bug？

// ✅ 这其实是可以接受的——Java 方法本来就可以返回 null
// 关键是 isSuccess() == true 表示方法执行成功了，只是返回了 null。
// 这和「没执行」是完全不同的。调用方通过 isSuccess() 区分。
```

### 错误 6：RpcFuture 超时后返回 TIMEOUT，但 Worker 后来还是返回了

```java
// 时序：
// ① Client 发请求，设超时 3 秒
// ② 3 秒到了，RpcFuture 返回 TIMEOUT
// ③ 第 4 秒 Worker 终于执行完了，发回 TASK_RESPONSE
// ④ RpcClient.onResponse() → future.complete(msg)
// → future 是谁？已经被 RpcFuture 丢弃了！找不到 CompletableFuture

// 但不会报错，因为 RpcClient.onResponse() 里：
// pendingRequests.remove(requestId) 在 cleanup() 后已经返回 null
// future.complete() 不会执行 → 没人拿到这个迟到的响应，被 GC 回收
```

**这不是 bug**，这是预期行为。超时后自动清理，迟到的响应无人认领、安全丢弃。但需要注意：Worker 端也可能因为这个请求「已经超时被丢弃」而白干了——后续课如果需要，可以加「请求取消」通知。

### 错误 7：序列化循环引用

```java
// ❌ RpcResponse 和 RpcRequest 互相引用？
// 不会。它们是单向的：
// RpcRequest → 包含 RpcInvocation
// RpcResponse → 独立，不引用 RpcRequest

// 但如果将来在 RpcResponse 里加了 requestId 字段做关联：
// → Gson 序列化没问题（单向引用）
// → 不要出现循环引用（A → B → A），Gson 默认不支持，会 StackOverflow
```

---

## 七、复习问题

1. 第 8 课已经有 RpcInvocation 了，为什么第 9 课还要加一层 RpcRequest 包装？
2. RpcStatus 枚举有哪 6 种状态？每种代表什么场景？
3. RpcResponse 为什么用静态工厂方法（`success()`/`error()`/`timeout()`）而不是直接 new？
4. `RpcFuture` 的超时处理做了哪两件 `CompletableFuture.get(timeout)` 没做的事？
5. Worker 端的 `ClientHandler.handleTaskRequest()` 怎么解析 RpcRequest？拿到后怎么传给 RpcDispatcher？
6. `RpcResponse.result` 的类型是 `Object`，Client 端拿到后怎么转成期望的返回类型？
7. 如果超时时间到了但 Worker 后来还是返回了响应，会发生什么？
8. `RpcClient.cleanup()` 方法是做什么的？不调用它会怎样？
9. Controller 的代码需要改吗？为什么？
10. 如果要在调用方用注解配置超时（如 `@RpcTimeout(5000)`），你会怎么设计？改哪些类？

### 参考答案

**1. 为什么加 RpcRequest 包装？**

职责分离。`RpcInvocation` 是「方法调用的描述」（调哪个类的哪个方法），这是应用层的概念。`RpcRequest` 是「一次 RPC 传输的元信息」（requestId、timeout、oneWay），这是传输层的概念。分开之后，重试时可以复用同一个 `RpcInvocation`、每次生成新的 `RpcRequest`（带新的 requestId），互不污染。

**2. RpcStatus 的 6 种状态：**

| 状态 | code | 含义 | 谁构造 |
|------|------|------|--------|
| SUCCESS | 0 | 调用成功 | Worker |
| TIMEOUT | 1 | 等超时了 | Client(RpcFuture) |
| NOT_FOUND | 2 | 服务接口未注册 | Worker |
| METHOD_NOT_FOUND | 3 | 方法不存在 | Worker |
| ERROR | 4 | 业务方法抛异常 | Worker |
| FRAMEWORK_ERROR | 5 | 框架级错误 | Client(RpcFuture) |

**3. 静态工厂 vs 构造函数：**

静态工厂方法名（`success`/`error`/`timeout`）本身就是文档，一看就知道意图。构造函数 `new RpcResponse(status, result, errorMessage)` 你需要记住三个参数分别是什么含义——而且成功时 errorMessage 该传什么？null？空字符串？容易出错。

**4. RpcFuture 做了两件额外的事：**

① 超时时自动调用 `rpcClient.cleanup(requestId)` 清理 `pendingRequests` 里的残留条目，防止内存泄漏。② 超时时不抛异常，而是返回一个结构化的 `RpcResponse(status=TIMEOUT)`，调用方用同一套逻辑处理成功和失败，不需要 try-catch。

**5. ClientHandler 处理链路：**

`GSON.fromJson(msg.getBody(), RpcRequest.class)` → `request.getInvocation()` → `GSON.toJson(invocation)` → `dispatcher.dispatch(invocationJson)`。Dispatcher 不感知 RpcRequest，只消费 RpcInvocation。ClientHandler 负责解开外层协议。

**6. Object result 转期望类型：**

`GSON.fromJson(GSON.toJson(response.getResult()), method.getReturnType())`。先 `toJson` 转回 JSON 字符串，再 `fromJson` 按目标类型解析。这一步看似低效（多了一次序列化/反序列化），但保证了类型安全——Gson 内部可能把 JSON 对象解析成 `LinkedTreeMap`，直接强转会报 `ClassCastException`。

**7. 迟到的响应：**

超时后 `cleanup(requestId)` 已经从 `pendingRequests` 中移除了该条目。Worker 的响应到达时，`RpcClient.onResponse()` 里 `pendingRequests.remove(requestId)` 返回 `null`，`future.complete()` 不会执行。迟到的响应没人认领，被 GC 回收。这是正常行为。

**8. cleanup() 方法：**

从 `pendingRequests` 这个 ConcurrentHashMap 中移除指定 `requestId` 的条目。不调用的话，超时的 future 永远不会被移除——`pendingRequests` 只增不减，长时间运行后 OOM。每 100 个超时请求就泄漏 100 个 `CompletableFuture<Message>` 对象。

**9. Controller 不需要改：**

Controller 只根据 `Message.type`（TASK_REQUEST/TASK_RESPONSE）做路由转发，不读 `Message.body`。body 从 `RpcInvocation JSON` 变成 `RpcRequest JSON`，从 `裸 String` 变成 `RpcResponse JSON`——对 Controller 完全透明。这再次验证了分层设计：传输层不关心应用层数据格式。

**10. 注解超时设计思路：**

```java
// 定义注解
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RpcTimeout {
    long value() default 30000;  // 毫秒
}

// RpcProxy.invoke() 里读取：
long timeoutMs = DEFAULT_TIMEOUT_MS;
RpcTimeout anno = method.getAnnotation(RpcTimeout.class);
if (anno != null) {
    timeoutMs = anno.value();
}
request.setTimeoutMs(timeoutMs);
```

改动点：新建 `RpcTimeout` 注解、在 RpcProxy 的 `invoke()` 里加注解读取逻辑。不涉及网络协议变更（timeoutMs 已经在 RpcRequest 里了）。

---

## 八、面试怎么聊

> "第 8 课做完动态代理之后，RPC 调用链路已经通了，但请求和响应的数据模型还是裸奔的——RpcInvocation 直接序列化塞进 body，错误信息是手动拼 JSON，超时写死在代码里。第 9 课我把这些「约定」升级为「协议」：定义了 RpcRequest / RpcResponse / RpcStatus 三个结构化模型，以及 RpcFuture 做超时控制。"

> "RpcRequest 的设计关键是把传输层元信息（requestId、timeout、oneWay）从 RpcInvocation 中分离出来。RpcInvocation 只管「调哪个类的哪个方法」这个应用层概念，RpcRequest 负责「这次调用怎么发、等多久」这些传输层概念。分离之后，重试时同一个 invocation 可以复用，每次生成新的 requestId。这和 Dubbo 的 RpcInvocation + Request 的分离思路一致。"

> "RpcStatus 是一个 6 状态的枚举，覆盖了 SUCCESS、TIMEOUT、NOT_FOUND、METHOD_NOT_FOUND、ERROR、FRAMEWORK_ERROR。Client 端拿到响应后先检查 status，再决定取 result 还是 errorMessage——不用再靠字符串匹配判断成功失败了。对标 Dubbo 的 Response 状态码。"

> "RpcFuture 封装了 CompletableFuture，超时时做了两件事：一是自动调用 rpcClient.cleanup() 清理 pendingRequests 残留，防止内存泄漏；二是返回一个结构化的 RpcResponse(status=TIMEOUT)，调用方不需要 try-catch，正常返回和超时返回都走同一套处理逻辑。这是超时控制从异常驱动到数据驱动的转变。"

> "有意思的是 Controller 依然一行代码不用改——body 从 RpcInvocation JSON 变成 RpcRequest JSON，从裸 String 变成 RpcResponse JSON，对只读 Message.type 做路由的 Controller 来说完全透明。这是我一开始做分层设计时的一个正确决策：传输层不关心应用层协议。"

---

## 九、下一步

做完本课，HeteroMesh 的 RPC 调用就有了正式的请求/响应协议和可配置的超时机制。第 10 课可以在这基础上做 **拦截器链（RpcInvocationChain）**——在 RPC 调用的前后插入日志、指标、限流、鉴权等横切逻辑，AOP 风格，不侵入业务代码。

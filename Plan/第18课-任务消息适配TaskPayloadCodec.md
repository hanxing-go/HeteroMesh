# 第 18 课：任务消息适配 TaskPayloadCodec

---

## 一、本课目标

前面我们已经有：

```text
TaskRequest / TaskResult / TaskMetadata
TaskStore
TaskScheduler
```

但现有网络层 `Message` 的 body 仍然只是一个 `String`。

这一课要做一个轻量适配层：

```text
TaskPayloadCodec
```

它负责：

```text
TaskRequest -> JSON String -> Message body
Message body -> TaskRequest

TaskResult -> JSON String -> Message body
Message body -> TaskResult
```

先不改 `ServerHandler` 主链路，只先把任务对象和 `Message.body` 的转换能力补齐。

---

## 二、为什么需要 TaskPayloadCodec

现在 `Message` 的结构是：

```java
private MessageType type;
private String requestId;
private String body;
```

`body` 是字符串，之前主要放的是 RPC 请求 JSON。

但任务调度阶段需要传：

```java
TaskRequest
TaskResult
```

如果每个 Handler 都自己写：

```java
new Gson().toJson(taskRequest)
new Gson().fromJson(body, TaskRequest.class)
```

会导致：

- 序列化逻辑散落在各处
- 后续字段改动难维护
- 测试不集中
- Handler 变得越来越杂

所以我们把这层转换集中到一个小类里。

---

## 三、新增文件

放在：

`heteromesh-common/src/main/java/com/heteromesh/task/`

| 文件 | 说明 |
|------|------|
| `TaskPayloadCodec.java` | TaskRequest / TaskResult 与 Message body 的 JSON 转换工具 |

我来写测试：

`heteromesh-common/src/test/java/com/heteromesh/task/TaskPayloadCodecTest.java`

---

## 四、TaskPayloadCodec 设计

### 4.1 推荐写成工具类

这个类不需要保存状态，只做转换，所以可以写成：

```java
public final class TaskPayloadCodec {
    private static final Gson GSON = new Gson();

    private TaskPayloadCodec() {
    }
}
```

`private` 构造器表示：

```text
这是工具类，不应该 new
```

### 4.2 方法设计

```java
public static String encodeRequest(TaskRequest request)
public static TaskRequest decodeRequest(String body)

public static String encodeResult(TaskResult result)
public static TaskResult decodeResult(String body)
```

---

## 五、代码骨架

位置：

`heteromesh-common/src/main/java/com/heteromesh/task/TaskPayloadCodec.java`

```java
package com.heteromesh.task;

import com.google.gson.Gson;

/**
 * TaskRequest / TaskResult 与 Message.body 的转换工具。
 */
public final class TaskPayloadCodec {
    private static final Gson GSON = new Gson();

    private TaskPayloadCodec() {
    }

    public static String encodeRequest(TaskRequest request) {
        // TODO: 使用 GSON.toJson(request)
        return null;
    }

    public static TaskRequest decodeRequest(String body) {
        // TODO: 使用 GSON.fromJson(body, TaskRequest.class)
        return null;
    }

    public static String encodeResult(TaskResult result) {
        // TODO: 使用 GSON.toJson(result)
        return null;
    }

    public static TaskResult decodeResult(String body) {
        // TODO: 使用 GSON.fromJson(body, TaskResult.class)
        return null;
    }
}
```

---

## 六、是否要处理 null

这一课先做简单版本。

可以选择：

```text
如果 body 为 null，让 Gson 自然处理
```

也可以做一点防御：

```java
if (body == null || body.isBlank()) {
    throw new IllegalArgumentException("Task payload body must not be empty");
}
```

我建议你加防御。原因是：

```text
Message.body 为空时，说明上游协议或调用方有问题
直接抛异常更容易定位
```

---

## 七、测试设计

我会写这些测试：

| 测试名 | 内容 |
|--------|------|
| shouldEncodeAndDecodeTaskRequest | TaskRequest 往返转换字段不丢 |
| shouldEncodeAndDecodeTaskResult | TaskResult 往返转换字段不丢 |
| shouldRejectBlankRequestBody | 空 request body 拒绝 |
| shouldRejectBlankResultBody | 空 result body 拒绝 |

---

## 八、本课完成标准

- 新增 `TaskPayloadCodec`
- 支持 TaskRequest 编码/解码
- 支持 TaskResult 编码/解码
- 空 body 有明确异常
- 测试通过


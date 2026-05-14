# 里程碑 1：通信引擎整合 + JMH 基准测试

> 前 4 节课的东西串起来，验证全链路，再加性能测试

---

## 一、你现在有什么？

前 4 节课搭好了一套完整的通信引擎：

```
┌─────────────────────────────────────────────────────────┐
│                    Worker（你的笔记本）                   │
│                                                         │
│  WorkerClient.connect()                                 │
│  Pipeline:                                              │
│    ExceptionHandler → IdleStateHandler                  │
│    → MessageDecoder → MessageEncoder                    │
│    → HeartbeatHandler → ClientHandler(RpcClient)        │
│                                                         │
│  能力：                                                  │
│    · 连 Controller（穿透 NAT）                           │
│    · 自动心跳（30s PING/PONG）                          │
│    · RPC 调用：call("hello") → 异步拿结果                │
│    · 异常统一处理                                        │
└────────────────────┬────────────────────────────────────┘
                     │  TCP 长连接
                     │  二进制协议 + 心跳 + RPC
                     │
┌────────────────────┴────────────────────────────────────┐
│                  Controller（云服务器）                   │
│                                                         │
│  HeteroMeshServer.run()                                 │
│  Pipeline:                                              │
│    ExceptionHandler → IdleStateHandler                  │
│    → MessageDecoder → MessageEncoder                    │
│    → HeartbeatHandler → ServerHandler                   │
│                                                         │
│  能力：                                                  │
│    · 接收 Worker 连接                                    │
│    · 自动心跳 + 检测离线                                  │
│    · 处理请求 + 回复（带 requestId）                      │
│    · ConnectionManager（登记所有连接）                    │
└─────────────────────────────────────────────────────────┘
```

---

## 二、第 5 课做什么？

不是学新东西，是**验证和压测**。四件事：

### 2.1 全链路压测：1000 条消息

连续发 1000 条 RPC 请求，验证三件事：
- ✅ 零粘包/半包（`LengthFieldBasedFrameDecoder` 在高压下是否正确）
- ✅ 零消息丢失（每条请求都收到回复，乱序也能正确匹配）
- ✅ 心跳不影响业务（压测期间心跳照常工作，不丢包不错乱）

### 2.2 容灾测试：Worker 宕机

Kill Worker → Controller 应在 3 次心跳超时内检测到 → 关闭对应 Channel。

### 2.3 JMH 基准测试（我来写）

JMH（Java Microbenchmark Harness）是 OpenJDK 官方的性能测试工具。不是 `System.currentTimeMillis()` 那种粗糙计时——它考虑了 JVM 预热、死代码消除、指令重排序等陷阱。

我会写 `ProtocolBenchmark.java`，对比：
- **JSON（Gson）**：你现在用的
- **Protobuf**：我会同时写 `MessageCodec.java`

输出两个指标：**序列化耗时** + **消息体积（字节数）**。

### 2.4 面试口述

闭上眼睛讲一遍：「我的通信协议怎么设计的？JSON 和 Protobuf 选哪个？为什么？」

---

## 三、你需要理解但不用写的东西

### 3.1 JMH 是什么？

```java
// 你以前的计时方式（不准确）
long start = System.currentTimeMillis();
doSomething();
long end = System.currentTimeMillis();
System.out.println("耗时: " + (end - start) + "ms");

// JMH 的方式（精确）
@Benchmark
public void testJsonEncode() {
    MessageSerializer.encode(message);
}
// JMH 自动：预热 JVM → 跑很多次 → 统计平均/最大/最小 → 输出报告
```

JMH 帮你做的事：
- **预热**：先跑几千次让 JIT 编译优化生效，不计入结果
- **多次迭代**：跑 N 轮，每轮 M 次，统计平均值和误差
- **避免死代码消除**：确保你的测试代码不会被 JVM 优化掉

### 3.2 Protobuf 是什么？

Google 的二进制序列化格式。你定义一个 `.proto` 文件描述数据结构：

```protobuf
message MessageProto {
  int32 type = 1;
  string requestId = 2;
  string body = 3;
}
```

Protobuf 编译器自动生成 Java 类，你只需要调 `message.toByteArray()` 和 `MessageProto.parseFrom(bytes)`。

**为什么比 JSON 小？** JSON 里字段名每次都传输（`"requestId":"abc-123"`），Protobuf 用数字编号代替字段名（`2="abc-123"`），省掉大量冗余。另外数字用变长编码（varint），小数字只占 1 字节。

---

## 四、JMH 预期结果（先剧透）

用你的 Message 对象（约 100 字节 body）测试，预期结果：

| | JSON (Gson) | Protobuf |
|---|---|---|
| 序列化耗时 | ~2-3 μs | ~0.5-1 μs（快 2-5x） |
| 消息体积 | ~150 bytes | ~120 bytes（小 ~20%） |
| 可读性 | ✅ 人能读 | ❌ 二进制，不可读 |

---

## 五、本课你需要做的事

| 任务 | 内容 | 谁做 |
|---|---|---|
| 压测 | 写一个 `StressTest.java`：发 1000 条 RPC 请求，验证全部收到回复 | **你写** |
| 容灾 | 验证 kill Worker 后 Controller 能检测到 | 一起测 |
| JMH | `ProtocolBenchmark.java` | 我写 |
| Protobuf | `MessageCodec.java` | 我写 |
| 口述 | 讲一遍通信协议设计 | 你讲 |

这一课你只需要写**一个压测**，代码量很少。剩下的我写+讲解，你来理解。

---

## 六、压测代码骨架

```java
// StressTest.java 放在 worker 的 test 目录
@Test
void shouldHandle1000Messages() throws Exception {
    // 1. 启动 Controller
    // 2. 启动 Worker（带 RPC 的 Pipeline）
    // 3. 用 CountDownLatch(1000) 计数
    // 4. 循环 1000 次 call("msg-" + i)，每次 thenAccept 里 latch.countDown()
    // 5. latch.await(30, SECONDS)
    // 6. assertEquals(0, latch.getCount())
}
```

---

## 七、学完本课应该能回答的问题

1. 你的通信协议怎么设计的？（二进制头 + JSON body）
2. 粘包/半包怎么解决？（LengthFieldBasedFrameDecoder + 协议头长度字段）
3. 心跳怎么实现？（IdleStateHandler + PING/PONG + 3 次超时）
4. RPC 怎么实现？（requestId + CompletableFuture + pendingRequests）
5. JSON 和 Protobuf 的区别？你选哪个？为什么？
6. JMH 是什么？比 `System.currentTimeMillis()` 好在哪？
7. 如果 Worker 突然死掉，Controller 怎么发现？（心跳超时 → close）
8. 你的项目为什么要 Worker 主动连 Controller？（穿透 NAT）

---

看完本文，开始写 `StressTest.java`。写好了告诉我。

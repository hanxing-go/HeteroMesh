# 第 2 课前置：面向接口编程 + Serializer/ServiceRegistry 接口抽取

---

## 一、这节课解决什么问题？

### 当前代码的"硬伤"

你的 `MessageSerializer` 和 `MessageCodec` 是两套独立的序列化实现，但它们：

```
MessageEncoder ──→ MessageSerializer.encode()   ← 硬编码，写死了用 JSON
MessageDecoder ──→ MessageSerializer.decode()   ← 同样写死

如果将来想切换序列化方案（JSON → Binary），改 MessageEncoder 吗？
如果有 10 种序列化方案，MessageEncoder 里写 10 个 if-else 吗？
```

同样的，`ConnectionManager` 用 `channel.id()` 做 key 管理连接：

```java
// 现在：key 是 channelId（Netty 内部 ID，重启就变）
channels.put(channel.id().asShortText(), channel);

// 未来：key 应该是 nodeId（Worker 逻辑标识，跨重启不变）
// nodeId = "worker-gpu-01"，由 Worker 注册时自报家门
```

**这节课要做的**：给序列化器和注册中心各抽一个接口出来，让上层代码只依赖接口，不依赖具体实现。

---

## 二、面向接口编程：Java 的灵魂

### 2.1 没有接口的世界

```
老板（Controller）说：我要用 JSON 发消息
工人只会用 JSON。想换 Binary？重新培训工人，或者换一个工人。
```

### 2.2 有接口的世界

```
老板（Controller）说：我需要一个会 serialize() 和 deserialize() 的人
不管你是 JSON 专家还是 Binary 专家，只要你会这两个方法，就能上岗。

老板只认「接口」，不认「具体是谁」。
```

### 2.3 代码对比

没有接口时（你现在的情况）：

```java
// MessageEncoder 硬编码依赖 MessageSerializer
ByteBuffer buffer = MessageSerializer.encode(msg);  // ← 焊死了 JSON
```

有接口后：

```java
// MessageEncoder 持有 Serializer 接口引用
private final Serializer serializer;  // ← 可以是任何实现

ByteBuffer buffer = serializer.serialize(msg);  // ← 不关心具体是谁
```

### 2.4 接口是什么

接口 = 一份**合同**，规定了"你必须会做什么"，但不规定"你怎么做"。

```java
// 这是一份合同
public interface Serializer {
    byte[] serialize(Message msg);           // 你必须会编码
    Message deserialize(byte[] bytes);       // 你必须会解码
    String name();                           // 你必须有个名字
}
// 你签了这份合同（implements Serializer），就必须实现这三个方法
// 至于具体怎么写 —— 随便你
```

类比：
- 你家墙上的电源插座 = 接口（220V，三孔）
- 插进去的电器 = 实现（灯泡、电脑、冰箱）
- 插座不在乎插的是什么，只要插头符合标准就能通电

---

## 三、你要写的接口

### 3.1 Serializer 接口

```java
package com.heteromesh.serializer;

import com.heteromesh.protocol.Message;

public interface Serializer {

    // 编码：Message → 字节数组
    byte[] serialize(Message message);

    // 解码：字节数组 → Message
    Message deserialize(byte[] data);

    // 序列化器名称（如 "json"、"binary"）
    String name();
}
```

**为什么要传 `Message` 而不是泛型？**

因为我们的场景已知：所有消息都是 `Message` 类型。泛型是等场景复杂了再加的。

### 3.2 ServiceRegistry 接口

```java
package com.heteromesh.registry;

public interface ServiceRegistry {

    // 注册一个节点
    void register(ServiceInstance instance);

    // 注销一个节点
    void unregister(String nodeId);

    // 查询一个节点
    ServiceInstance lookup(String nodeId);

    // 获取所有在线节点
    List<ServiceInstance> getAllInstances();

    // 获取在线节点数量
    int size();
}
```

---

## 四、两个实现：JsonSerializer 和 BinarySerializer

### 4.1 代码迁移策略

你不需要从头写，只需要把现有代码**搬家 + 加 implements**：

| 现有关 | 搬家到 | 改什么 |
|--------|--------|--------|
| `MessageSerializer.encode()` | `JsonSerializer.serialize()` | 返回类型从 `ByteBuffer` 改 `byte[]`，加 `@Override` |
| `MessageSerializer.decode()` | `JsonSerializer.deserialize()` | 参数从 `ByteBuffer` 改 `byte[]`，加 `@Override` |
| `MessageCodec.encode()` | `BinarySerializer.serialize()` | 加 `@Override`，不变 |
| `MessageCodec.decode()` | `BinarySerializer.deserialize()` | 加 `@Override`，不变 |

**注意：返回类型统一为 `byte[]`，不是 `ByteBuffer`。**

为什么？接口要统一。`ByteBuffer` 是 NIO 特有的，网络层用；`byte[]` 是纯数据，和 I/O 无关。

### 4.2 两个类的文件结构

JsonSerializer 大概长这样（骨架，不是说让你照抄）：

```java
package com.heteromesh.serializer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import java.nio.charset.StandardCharsets;

public class JsonSerializer implements Serializer {

    private static final Gson gson = new Gson();
    // 注意：HEADER_LENGTH 和 MAGIC_NUMBER 不再属于 Serializer
    // 它们属于编解码器（MessageEncoder/Decoder），后面会处理

    @Override
    public byte[] serialize(Message message) {
        // 把 MessageSerializer.encode() 的逻辑搬过来
        // 但要改造：原来是写 ByteBuffer + 10 字节协议头，现在只产 JSON 字节
        // ...
    }

    @Override
    public Message deserialize(byte[] data) {
        // 把 MessageSerializer.decode() 的逻辑搬过来
        // ...
    }

    @Override
    public String name() {
        return "json";
    }
}
```

BinarySerializer 结构：

```java
package com.heteromesh.serializer;

import com.heteromesh.protocol.Message;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class BinarySerializer implements Serializer {

    @Override
    public byte[] serialize(Message message) {
        // 搬 MessageCodec.encode() 的逻辑
        // 用 varint + length-delimited 编码
        // ...
    }

    @Override
    public Message deserialize(byte[] data) {
        // 搬 MessageCodec.decode() 的逻辑
        // ...
    }

    @Override
    public String name() {
        return "binary";
    }

    // varint 工具方法也搬过来
    private static void writeVarint(ByteBuffer buf, int value) { ... }
    private static int readVarint(ByteBuffer buf) { ... }
}
```

---

## 五、ServiceInstance 节点模型

ServiceInstance 是一个纯数据类，描述一个 Worker 节点：

```java
package com.heteromesh.registry;

public class ServiceInstance {

    private String nodeId;       // 节点唯一标识，如 "worker-gpu-01"
    private String host;         // Worker 的 IP 地址
    private int port;            // Worker 的端口
    private long registerTime;   // 注册时间戳
    private long lastHeartbeat;  // 最后一次心跳时间戳

    // 构造方法 + getter/setter ...

    // Worker 判活逻辑
    public boolean isAlive(long timeoutMs) {
        return System.currentTimeMillis() - lastHeartbeat < timeoutMs;
    }
}
```

---

## 六、InMemoryServiceRegistry 替代 ConnectionManager

当前 `ConnectionManager` 的问题：
- key 是 `channel.id()`（Netty 内部生成的、连接断开就变的东西）
- 没有节点元数据（host、port、注册时间、状态）
- 不区分"连接存在"和"节点已注册"——这两个是不同的概念

新的 `InMemoryServiceRegistry`：

```java
package com.heteromesh.registry;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryServiceRegistry implements ServiceRegistry {

    // nodeId → 节点信息
    private final ConcurrentHashMap<String, ServiceInstance> instances = new ConcurrentHashMap<>();

    @Override
    public void register(ServiceInstance instance) {
        // 存入 map，设置注册时间
    }

    @Override
    public void unregister(String nodeId) {
        // 从 map 中移除
    }

    @Override
    public ServiceInstance lookup(String nodeId) {
        // 查找节点
    }

    @Override
    public List<ServiceInstance> getAllInstances() {
        // 返回所有节点快照
    }

    @Override
    public int size() {
        return instances.size();
    }
}
```

**ConnectionManager 要不要删？** 先留着不动。这节课只需要让 InMemoryServiceRegistry 写出来并通过测试，和第 6 课（节点注册协议）时再替换。

---

## 七、你需要同时调整 MessageEncoder/MessageDecoder

Serializer 接口返回 `byte[]` 了，但 MessageEncoder 还调用 `MessageSerializer.encode(msg)` 拿 `ByteBuffer`。所以这节课也要改编解码器：

**MessageEncoder 改动思路：**

```java
// 改前
ByteBuffer buffer = MessageSerializer.encode(msg);
out.writeBytes(buffer);

// 改后
// 接受一个 Serializer（从构造函数传入）
byte[] bytes = serializer.serialize(msg);
// 然后手动写入协议头（Magic + Version + Type + Length）...
out.writeInt(MAGIC_NUMBER);
out.writeByte(VERSION);
out.writeByte(msg.getType().getCode());
out.writeInt(bytes.length);
out.writeBytes(bytes);
```

**MessageDecoder 改动思路类似**：先读协议头，再读 body，然后 `serializer.deserialize(bodyBytes)`。

> ⚠️ 这一部分是这课最复杂的。我会在 review 时重点看。

---

## 八、这节课的文件清单（全部你写）

在 `heteromesh-common/src/main/java/com/heteromesh/` 下：

**新建 serializer 包：**

| 文件 | 说明 |
|------|------|
| `serializer/Serializer.java` | 接口，3 个方法 |
| `serializer/JsonSerializer.java` | JSON 实现，搬 MessageSerializer 逻辑 |
| `serializer/BinarySerializer.java` | Binary 实现，搬 MessageCodec 逻辑 |

**新建 registry 包：**

| 文件 | 说明 |
|------|------|
| `registry/ServiceInstance.java` | 节点模型 POJO |
| `registry/ServiceRegistry.java` | 接口，5 个方法 |
| `registry/InMemoryServiceRegistry.java` | 基于 ConcurrentHashMap 的实现 |

**修改现有文件：**

| 文件 | 改动 |
|------|------|
| `protocol/MessageEncoder.java` | 不再硬编码调 MessageSerializer，改成接受 Serializer 接口 |
| `protocol/MessageDecoder.java` | 同上 |

**新建测试（在 test 目录下）：**

| 文件 | 测试什么 |
|------|---------|
| `serializer/JsonSerializerTest.java` | encode → decode 往返，验证数据一致 |
| `serializer/BinarySerializerTest.java` | 同上 |
| `registry/InMemoryServiceRegistryTest.java` | register/lookup/unregister/size |

---

## 九、Pipeline 位置：序列化在整个链路中的角色

```
发送端                                   接收端
┌────────────────────┐                 ┌────────────────────┐
│  业务代码           │                 │  业务代码           │
│  Message msg = ...  │                 │  Message msg = ...  │
└──────┬─────────────┘                 └────────▲────────────┘
       │ Serializer.serialize(msg)              │ Serializer.deserialize(bytes)
       ▼                                        │
┌────────────────────┐                 ┌────────────────────┐
│  Serializer (接口)  │                 │  Serializer (接口)  │
│  → byte[]           │                 │  ← byte[]           │
└──────┬─────────────┘                 └────────▲────────────┘
       │ 产出了纯数据                           │ 收到了纯数据
       ▼                                        │
┌────────────────────┐                 ┌────────────────────┐
│  MessageEncoder     │   ──网络──→    │  MessageDecoder     │
│  写协议头 + bytes   │                │  读协议头 + bytes   │
└────────────────────┘                 └────────────────────┘
```

**关键理解**：序列化器和编码器是两层。
- Serializer：负责 "Message → 字节"，纯数据转换，与网络无关
- Encoder/Decoder：负责 "加/拆协议头 + 写到网线/从网线读"，与网络有关

这就像：
- Serializer = 把信纸折好放进信封（内容编码）
- Encoder = 在信封上写地址 + 贴邮票 + 扔进邮筒（传输包装）

---

## 十、新包结构预览

```
com.heteromesh/
├── protocol/             ← 不变
│   ├── Message.java
│   ├── MessageType.java
│   ├── MessageEncoder.java    ← 要改：接受 Serializer
│   ├── MessageDecoder.java    ← 要改：接受 Serializer
│   └── (MessageSerializer.java, MessageCodec.java 逻辑搬家后可删)
├── serializer/           ← 新建
│   ├── Serializer.java
│   ├── JsonSerializer.java
│   └── BinarySerializer.java
├── registry/             ← 新建
│   ├── ServiceInstance.java
│   ├── ServiceRegistry.java
│   └── InMemoryServiceRegistry.java
└── transport/            ← 不变
    ├── ExceptionHandler.java
    ├── HeartbeatHandler.java
    ├── ConnectionManager.java
    └── RpcClient.java
```

---

## 十一、常见错误预警

1. **忘记 @Override**：实现接口方法时必须加 `@Override` 注解，编译器帮你检查是否真的覆盖了

2. **JsonSerializer 里忘了把 `ByteBuffer` 改成 `byte[]`**：接口返回值是 `byte[]`，你 return 的是 `ByteBuffer`，编译不过

3. **测试往返时只测了 encode，忘了测 decode**：
   ```java
   // ✅ 正确的往返测试
   byte[] bytes = serializer.serialize(original);
   Message restored = serializer.deserialize(bytes);
   assertEquals(original.getBody(), restored.getBody());
   ```

4. **MessageEncoder 写协议头时字节序要一致**：编码用大端（BigEndian），解码也必须大端

---

## 十二、复习问题

1. 接口和抽象类的区别是什么？Serializer 为什么用接口而不是抽象类？
2. `implements Serializer` 后，编译器如何检查你是否实现了所有方法？
3. 序列化和编解码的分工边界在哪里？为什么 Serializer 不负责写协议头？
4. 一个接口有 3 个实现时，上层代码怎么知道该用哪个？（提示：下节课 SPI）
5. `ConcurrentHashMap` 和 `HashMap` 的区别是什么？ServiceRegistry 为什么用前者？
6. `ServiceInstance` 里的 `lastHeartbeat` 字段是谁来更新的？
7. 如果把 `JsonSerializer` 里的 Gson 换成 Jackson，需要改接口吗？需要改上层代码吗？
8. `Serializer` 接口的 `serialize` 返回 `byte[]` 而不是 `ByteBuffer`，这是为什么？
9. `ServiceRegistry` 的 `getAllInstances()` 返回的 List 是原始集合还是副本？有线程安全问题吗？
10. 如果你要加第三种序列化方案（比如 Protobuf），需要改几个文件？

---

## 十三、面试怎么聊

> "做完基础通信后，我做的第一件事是接口化。把 JSON 和 Binary 两套序列化方案抽了一个统一的 Serializer 接口，MessageEncoder 只依赖接口不依赖实现。这符合依赖倒置原则——高层模块不依赖低层模块，都依赖抽象。同样的思路用在注册中心上，ServiceRegistry 接口背后是 InMemoryServiceRegistry，未来想换成 ZooKeeper 或 Nacos 实现，改一行构造就行。这种设计在 Dubbo、Spring 里到处都是。"

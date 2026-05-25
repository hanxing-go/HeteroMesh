# 第 4 课：序列化策略路由 + YAML 配置化

---

## 上下文（给新会话看）

- **项目**：HeteroMesh，从零手写分布式 RPC 框架，Java 21 + Netty + Maven 多模块
- **路径**：`C:\Users\12099\Desktop\HeteroMesh\HeteroMesh`
- **已完成**：第 3 课（SPI 插件机制 + SerializerFactory），现在 `SerializerFactory.getSerializer("json")` 一行代码就能拿到实现
- **当前状态**：有 JSON 和 Binary 两个序列化器，SPI 自动发现，但每次都要手动指定用哪个——能不能让系统自动选？

---

## 一、这节课解决什么问题？

### 当前问题

```java
// 发送心跳时：
Serializer s = SerializerFactory.getSerializer("binary");  // 手动选

// 发送 RPC 请求时：
Serializer s = SerializerFactory.getSerializer("json");    // 又手动选

// 接收消息时：
// 我收到了一个 Message，但我怎么知道发送方用什么序列化器编码的？
// 用 JSON 反序列化 Binary 编码的 bytes？→ 直接报错
```

三个问题：
1. **发送方**：每次都要手动选序列化器，"心跳用 binary"这个知识散落在各段代码里
2. **接收方**：不知道发送方用什么序列化器（消息头里没有标识）
3. **切换方案**：想把心跳从 binary 改成 json？全局搜索 `"binary"` 逐个改

### 目标

```java
// 发送时：自动选，一行代码
Serializer s = SerializerRouter.select(msg);

// 接收时：从消息头知道用什么反序列化
byte serializerCode = header[5];  // 协议头第 5 字节存序列化器 ID
Serializer s = SerializerRouter.getByCode(serializerCode);

// 调配规则：从 YAML 配置文件读取，改配置不用改代码
// application.yml:
//   serializer:
//     routing:
//       PING: binary
//       REQUEST: json
```

---

## 二、设计思路：策略模式的三层结构

### 2.1 总览

```
┌──────────────────────────────────────────────────────────┐
│                  SerializerRouter                        │
│  职责：根据消息类型 / 配置 / code 选择合适的序列化器        │
│  这是「决策层」——决定用谁                                  │
└──────────┬──────────────────────────┬───────────────────┘
           │                          │
    ┌──────▼──────┐           ┌──────▼──────┐
    │ MessageType  │           │ SerializerCode│
    │ → serializer│           │ → serializer │
    │ 映射规则     │           │ 协议头映射    │
    └─────────────┘           └──────────────┘
           │                          │
           └──────────┬───────────────┘
                      ▼
┌──────────────────────────────────────────────────────────┐
│                  SerializerFactory                       │
│  职责：发现 classpath 上所有 Serializer 实现              │
│  这是「注册层」——知道有哪些人可选                          │
└──────────┬───────────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────┐
│        JsonSerializer          BinarySerializer          │
│  这是「执行层」——真正干活的人                             │
└──────────────────────────────────────────────────────────┘
```

**类比**：
- `SerializerRouter` = 餐厅经理（顾客是心跳消息 → 安排 Binary 厨师）
- `SerializerFactory` = 员工花名册（知道店里有哪些厨师）
- `Serializer` 实现 = 两个厨师（每个擅长做不同的菜）

### 2.2 为什么不是 `if-else` 堆在 MessageEncoder 里？

```java
// ❌ 把所有决策逻辑堆在一个地方
public class MessageEncoder {
    void encode(Message msg) {
        if (msg.getType() == MessageType.PING) {
            serializer = binary;  // 心跳用 binary
        } else if (msg.getType() == MessageType.REQUEST) {
            serializer = json;    // RPC 用 json
        } else {
            serializer = json;    // 其他用 json
        }
    }
}
// → 将来加消息类型/序列化器，这个 if-else 会无限膨胀
// → 违反开闭原则

// ✅ 把决策规则抽到一个专门的类
public class SerializerRouter {
    public Serializer select(Message msg) {
        return config.getSerializerFor(msg.getType());
    }
}
// → MessageEncoder 不需要改，只改配置或 SerializerRouter
```

**这就是策略模式的核心：把「选谁」和「怎么用」分开。**

---

## 三、协议头改造：加 1 字节序列化器标识

### 3.1 现在的协议头（10 字节）

```
 0         4         5         6         7             11
┌─────────┬─────────┬─────────┬─────────┬──────────────┐
│  Magic  │ Version │  Type   │  Length │     Body     │
│ 4 bytes │ 1 byte  │ 1 byte  │ 4 bytes │  N bytes     │
└─────────┴─────────┴─────────┴─────────┴──────────────┘
```

### 3.2 改造后的协议头（11 字节）

```
 0         4         5         6         7         8         12
┌─────────┬─────────┬─────────┬─────────┬─────────┬────────────┐
│  Magic  │ Version │SerCode  │  Type   │  Length │    Body    │
│ 4 bytes │ 1 byte  │ 1 byte  │ 1 byte  │ 4 bytes │  N bytes   │
└─────────┴─────────┴─────────┴─────────┴─────────┴────────────┘
                         ↑ 新增：SerializerCode
```

### 3.3 SerializerCode 枚举

```java
package com.heteromesh.serializer;

public enum SerializerCode {
    JSON((byte) 0),
    BINARY((byte) 1);

    private final byte code;

    SerializerCode(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    // 根据 code 反向查找枚举
    public static SerializerCode fromCode(byte code) {
        for (SerializerCode sc : values()) {
            if (sc.code == code) return sc;
        }
        throw new IllegalArgumentException("Unknown serializer code: " + code);
    }
}
```

### 3.4 为什么加这 1 个字节很重要？

```java
// 没有这 1 字节时：
// 接收方收到 bytes，不知道发送方用什么编码
// 只能「猜」或者「约定死用某一种」→ 不灵活

// 有这 1 字节后：
byte code = buffer.readByte();  // 读第 6 个字节
Serializer s = SerializerRouter.getByCode(code);  // 拿到对应的序列化器
Message msg = s.deserialize(bodyBytes);  // 正确反序列化
```

**这就是协议的自描述性**：消息本身携带了"我是怎么编码的"信息，接收方不需要猜。这和 HTTP 的 `Content-Type: application/json` 是同一个道理。

---

## 四、YAML 配置化

### 4.1 为什么需要外部化配置？

```java
// 现在：规则写死在代码里
// 想把心跳从 binary 改成 json → 改代码、重新编译、重新部署

// 目标：规则在 YAML 文件里
// 想改 → 改一行配置文件 → 重启即可，不用重新编译
```

### 4.2 配置文件

位置：`heteromesh-common/src/main/resources/application.yml`

```yaml
# HeteroMesh 配置
heteromesh:
  # 序列化器配置
  serializer:
    # 默认序列化器（当消息类型没有被明确配置时使用）
    default: binary

    # 按消息类型路由
    routing:
      PING: binary         # 心跳用 Binary（最紧凑）
      PONG: binary
      HEARTBEAT: binary
      REQUEST: json        # 业务请求用 JSON（可读，方便调试）
      RESPONSE: json
      REGISTER: json       # 注册消息用 JSON（一次性，可读优先）
      REGISTER_ACK: json

  # 网络配置（后续课用）
  server:
    port: 9090
    boss-threads: 1
    worker-threads: 4

  worker:
    heartbeat-interval-seconds: 5
    heartbeat-timeout-seconds: 15
```

### 4.3 ConfigLoader：读取 YAML

用 SnakeYAML 读取配置。：

```java
package com.heteromesh.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

public class ConfigLoader {

    private static final String CONFIG_FILE = "application.yml";
    private static volatile Map<String, Object> config;

    public static Map<String, Object> load() {
        if (config != null) return config;
        synchronized (ConfigLoader.class) {
            if (config != null) return config;
            try (InputStream in = ConfigLoader.class.getClassLoader()
                    .getResourceAsStream(CONFIG_FILE)) {
                if (in == null) {
                    throw new IllegalStateException("Config file not found: " + CONFIG_FILE);
                }
                Yaml yaml = new Yaml();
                config = yaml.load(in);
                return config;
            } catch (Exception e) {
                throw new RuntimeException("Failed to load config", e);
            }
        }
    }

    public static Map<String, Object> getConfig() {
        if (config == null) load();
        return config;
    }
}
```

**双重检查锁的原因**：和 SpiExtensionLoader 一模一样——只有第一次调用需要读文件，之后 99.9% 的调用直接从缓存拿，无需加锁。

### 4.4 SerializationConfig：配置的薄封装

```java
package com.heteromesh.config;

import com.heteromesh.protocol.MessageType;
import com.heteromesh.serializer.SerializerCode;

import java.util.Map;

public class SerializationConfig {

    @SuppressWarnings("unchecked")
    public static SerializerCode getSerializerCodeFor(MessageType type) {
        Map<String, Object> config = ConfigLoader.getConfig();
        Map<String, Object> heteromesh = (Map<String, Object>) config.get("heteromesh");
        Map<String, Object> serializer = (Map<String, Object>) heteromesh.get("serializer");
        Map<String, String> routing = (Map<String, String>) serializer.get("routing");

        String serializerName = routing.get(type.name());
        if (serializerName == null) {
            serializerName = (String) serializer.get("default");
        }
        return SerializerCode.valueOf(serializerName.toUpperCase());
    }
}
```

---

## 五、SerializerRouter（核心，~60 行）

```java
package com.heteromesh.serializer;

import com.heteromesh.config.SerializationConfig;
import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;

import java.util.EnumMap;
import java.util.Map;

public class SerializerRouter {

    // code → 序列化器实例（通过 SerializerFactory 加载）
    private static final Map<SerializerCode, Serializer> CODE_MAP =
            new EnumMap<>(SerializerCode.class);

    // 消息类型 → 序列化器 code（从 YAML 读）
    private static final Map<MessageType, SerializerCode> TYPE_MAP =
            new EnumMap<>(MessageType.class);

    static {
        // 初始化 code → serializer（从 SPI 自动发现）
        CODE_MAP.put(SerializerCode.JSON, SerializerFactory.getSerializer("json"));
        CODE_MAP.put(SerializerCode.BINARY, SerializerFactory.getSerializer("binary"));

        // 初始化 type → code（从 YAML 配置读）
        for (MessageType type : MessageType.values()) {
            SerializerCode code = SerializationConfig.getSerializerCodeFor(type);
            TYPE_MAP.put(type, code);
        }
    }

    // 根据消息类型选择序列化器（发送时用）
    public static Serializer select(Message message) {
        SerializerCode code = TYPE_MAP.get(message.getType());
        if (code == null) {
            code = SerializerCode.BINARY;  // 兜底：没配置的消息类型默认用 Binary
        }
        return CODE_MAP.get(code);
    }

    // 根据协议头的 code 获取序列化器（接收时用）
    public static Serializer getByCode(byte code) {
        SerializerCode sc = SerializerCode.fromCode(code);
        return CODE_MAP.get(sc);
    }

    // 根据消息类型获取 code（写入协议头时用）
    public static byte getCode(Message message) {
        SerializerCode code = TYPE_MAP.get(message.getType());
        if (code == null) code = SerializerCode.BINARY;
        return code.getCode();
    }
}
```

### 为什么用 EnumMap？

```java
// HashMap：O(1)，但需要 hash 计算 + 处理碰撞
// EnumMap：O(1)，但内部用数组，枚举的 ordinal() 作下标——更快

// 对于 SerializerCode（2 个值）和 MessageType（<8 个值）
// EnumMap 是最佳选择：常数时间 + 无碰撞 + 内存占用极小
```

---

## 六、改造 MessageEncoder / MessageDecoder

### 6.1 MessageEncoder 改动

```java
// 改前：
out.writeInt(MAGIC_NUMBER);              // 4 bytes magic
out.writeByte(VERSION);                  // 1 byte version
out.writeByte(msg.getType().getCode());  // 1 byte type
out.writeInt(bytes.length);              // 4 bytes length
out.writeBytes(bytes);                   // N bytes body

// 改后：
out.writeInt(MAGIC_NUMBER);                    // 4 bytes magic
out.writeByte(VERSION);                        // 1 byte version
out.writeByte(SerializerRouter.getCode(msg));  // 1 byte serializerCode ← 新增
out.writeByte(msg.getType().getCode());        // 1 byte type
out.writeInt(bytes.length);                    // 4 bytes length
out.writeBytes(bytes);                         // N bytes body

// 序列化也改用 Router：
byte[] bytes = SerializerRouter.select(msg).serialize(msg);
```

### 6.2 MessageDecoder 改动

```java
// 改前（读 10 字节头）：
int magic = in.readInt();
byte version = in.readByte();
byte typeCode = in.readByte();
int length = in.readInt();
// 硬编码反序列化：
Message msg = someSerializer.deserialize(bodyBytes);  // ← someSerializer 哪来的？

// 改后（读 11 字节头）：
int magic = in.readInt();
byte version = in.readByte();
byte serializerCode = in.readByte();        // ← 新增：先读序列化器标识
byte typeCode = in.readByte();
int length = in.readInt();
// 根据协议头里的 code 选序列化器：
Serializer serializer = SerializerRouter.getByCode(serializerCode);
Message msg = serializer.deserialize(bodyBytes);
```

### 6.3 协议头常量更新

```java
// MessageEncoder.java / MessageDecoder.java 中
// 改前：
public static final int HEADER_LENGTH = 10;

// 改后：
public static final int HEADER_LENGTH = 11;
```

---

## 七、这节课的文件清单

`heteromesh-common/src/main/java/com/heteromesh/` 下：

| 文件 | 说明 | 类型 |
|------|------|------|
| `serializer/SerializerCode.java` | code 枚举，JSON(0) / BINARY(1)（~18 行） | 新建 |
| `serializer/SerializerRouter.java` | 路由核心（~55 行） | 新建 |
| `config/ConfigLoader.java` | YAML 加载器，双重检查锁（~35 行） | 新建 |
| `config/SerializationConfig.java` | 序列化配置封装（~20 行） | 新建 |
| `protocol/MessageEncoder.java` | 加 serializerCode 字节 + 用 Router | 修改 |
| `protocol/MessageDecoder.java` | 读 serializerCode 字节 + 用 Router | 修改 |

需要加的 Maven 依赖（父 POM `dependencyManagement` + `heteromesh-common/pom.xml`）：

```xml
<!-- 父 POM -->
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
    <version>2.3</version>
</dependency>

<!-- heteromesh-common/pom.xml（不需要版本号，从父 POM 继承） -->
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
</dependency>
```

> **为什么之前说 Netty 自带？** `netty-codec-http2` 确实依赖 SnakeYAML，但 Maven 里标的是 `<optional>true</optional>`——可选依赖不会自动传递到你的项目。所以需要手动加。

`heteromesh-common/src/main/resources/` 下：

| 文件 | 说明 |
|------|------|
| `application.yml` | 全局配置（序列化路由规则 + 后续的网络/心跳配置） |

测试文件（`src/test/java/...`）：

| 文件 | 测试什么 |
|------|---------|
| `serializer/SerializerRouterTest.java` | PING→binary, REQUEST→json，往返编解码一致性 |
| `config/ConfigLoaderTest.java` | YAML 加载成功 |
| `config/SerializationConfigTest.java` | 配置读取正确，未知类型回退到 default |

---

## 八、改造范围：涉及的所有现有文件

第 3 课你用 `SerializerFactory.getSerializer("xxx")` 替换了硬编码，现在要改成用 `SerializerRouter.select(msg)` 或 `SerializerRouter.getByCode(code)`。

需要二次改造的文件：

| 文件 | 改动内容 |
|------|---------|
| `HeteroMeshServer.java` | 构造 MessageEncoder/Decoder 时不再需要传 Serializer 参数 |
| `WorkerClient.java` | 同上 |
| `MessageEncoder.java` | 用 `SerializerRouter` 替换直接调 `serializer.serialize()` |
| `MessageDecoder.java` | 用 `SerializerRouter.getByCode()` 替换 `serializer.deserialize()` |
| 测试文件 | `StressTest`, `RpcIntegrationTest`, `ClientServerIntegrationTest`, `HeartbeatIntegrationTest` |

**注意**：这一步改 6+ 个文件，但改动逻辑简单——把 `serializer.xxx()` 换成 `SerializerRouter.xxx()`。IDE 全局搜索替换可以大幅加速。

---

## 九、常见错误预警

### 错误 1：协议头长度忘改

```java
// 你改了 Encoder 加了 1 个字节，但 Decoder 的 HEADER_LENGTH 还是 10
// → 粘包/半包处理错位，所有消息解析失败
public static final int HEADER_LENGTH = 10;  // ❌ 改成 11
public static final int HEADER_LENGTH = 11;  // ✅
```

### 错误 2：SerializerCode 和 SerializerRouter 配置不一致

```java
// SerializerCode 里：
JSON((byte) 0)

// SerializerRouter 里：
CODE_MAP.put(SerializerCode.JSON, SerializerFactory.getSerializer("binary")); // ❌
CODE_MAP.put(SerializerCode.JSON, SerializerFactory.getSerializer("json"));   // ✅
```

### 错误 3：YAML 文件路径错误

```
# 正确位置：heteromesh-common/src/main/resources/application.yml
# 编译后在 target/classes/application.yml（classpath 根）
# 不是在 src/main/java 下面！
```

### 错误 4：枚举大小写不匹配

```java
// YAML 里写的是 "binary"（小写）
// SerializerCode.valueOf("BINARY")  ← 正确
// SerializerCode.valueOf("binary")  ← 抛出 IllegalArgumentException！

// 所以在 SerializationConfig 里要做 toUpperCase()
serializerName.toUpperCase()
```

### 错误 5：忘记兜底逻辑

```java
// 如果 YAML 里漏配了某种消息类型 → TYPE_MAP.get(type) 返回 null
// → select() 返回 null → NullPointerException

// 必须有兜底：
if (code == null) code = SerializerCode.BINARY;  // 默认用 Binary
```

### 错误 6：Encoder 和 Decoder 字节顺序不一致

```java
// Encoder 写：Magic(4) → Version(1) → SerializerCode(1) → Type(1) → Length(4)
// Decoder 读：Magic(4) → Version(1) → SerializerCode(1) → Type(1) → Length(4)
// ↑ 必须严格一致，否则读到的 serializerCode 其实是 typeCode 的值
```

---

## 十、复习问题

1. SerializerRouter 和 SerializerFactory 的职责有什么区别？为什么需要两个类？
2. 协议头加 1 字节 SerializerCode 解决了什么问题？没有它为什么不行？
3. `EnumMap` 和 `HashMap` 有什么区别？Router 为什么选 `EnumMap`？
4. YAML 配置里为什么用 `PING: binary` 而不是 `0: binary`？哪种更可维护？
5. `ConfigLoader` 为什么用双重检查锁？和 SpiExtensionLoader 的设计模式一样吗？
6. 如果在 YAML 里加一条 `TASK_REQUEST: json`，需要改代码吗？
7. 发送方和接收方分别怎么决定用哪个序列化器？
8. `SerializerCode` 枚举里的 `fromCode()` 是干什么用的？什么时候调用？
9. 这个设计模式叫什么？体现了哪些设计原则？
10. 将来如果加了第三种序列化方案（比如 Protobuf），需要在哪些地方改动？

---

## 十一、面试怎么聊

> "两个序列化器都有了之后，下一个问题是：怎么自动选？不能每次手动指定。我做了两件事：第一，在协议头里加了 1 个字节的 SerializerCode，这样接收方看到消息头就知道用什么反序列化，不需要约定死——这和 HTTP 的 Content-Type 头是同一个道理；第二，实现了一个 SerializerRouter，它根据消息类型自动选择序列化器——心跳永远 Binary（最小开销）、业务 RPC 用 JSON（可读方便调试）。路由规则不写死在代码里，而是从 YAML 配置文件读取，改一行配置就能切换方案。"

> "架构上用了策略模式：SerializerRouter 是策略选择器，Serializer 接口是策略接口，两个实现类是具体策略。加新方案时，只需要写实现类、在 SPI 配置文件加一行、在 YAML 里配一条规则，不改任何核心代码。ConfigLoader 用了双重检查锁，和 SpiExtensionLoader 一样的模式——第一次加载后缓存，后续调用零开销。"

---

## 十二、阶段 1 总结

做完这节课，**阶段 1（基础设施重构）**就全部完成了。回顾一下这 4 节课：

```
第 1 课：SLF4J 日志     → System.out 消失，日志规范输出
第 2 课：接口抽取       → Serializer/ServiceRegistry 接口化，上层解耦
第 3 课：SPI 机制       → 自动发现实现类，一行代码获取
第 4 课：策略路由+YAML  → 自动选择 + 配置化（本课）
```

做完这 4 课，你的 `heteromesh-common` 模块从一堆硬编码 demo 变成了一个有接口、有 SPI 发现、有策略路由、有外部配置的基础库。这是「基础设施」的真正含义——为后面的 Controller / Worker / 负载均衡 / 容错提供可插拔的底层能力。

---

## 十三、下一步：进入阶段 2

做完本课，阶段 1 完工。下一课开始进入 **阶段 2（注册中心 + 负载均衡）**——让 Controller 真正管理 Worker 节点：

| 课 | 内容 | 用到的阶段 1 成果 |
|------|------|-------------------|
| **第 5 课** | **节点注册协议 + 心跳维护** | SerializerRouter（心跳自动用 Binary） |
| 第 6 课 | 一致性哈希 + 虚拟节点 | ServiceRegistry 接口 |
| 第 7 课 | 负载均衡策略集（随机/轮询/加权） | SPI（负载均衡器也是 SPI 插件） |

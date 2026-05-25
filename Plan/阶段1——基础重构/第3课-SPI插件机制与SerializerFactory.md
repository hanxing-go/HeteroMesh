# 第 3 课：SPI 插件机制 + SerializerFactory

---

## 上下文（给新会话看）

- **项目**：HeteroMesh，从零手写分布式 RPC 框架，Java 21 + Netty + Maven 多模块
- **路径**：`C:\Users\12099\Desktop\HeteroMesh\HeteroMesh`
- **已完成**：第 1-2 课（日志重构、Serializer 接口 + Json/Binary 实现、ServiceRegistry 接口 + InMemory 实现）
- **当前状态**：`new JsonSerializer()` / `new BinarySerializer()` 硬编码在各处，这课要解决「自动发现实现类」

---

## 一、这节课解决什么问题？

### 当前问题

```java
// HeteroMeshServer.java 第 36 行
Serializer serializer = new BinarySerializer();  // ← 硬编码！

// WorkerClient.java 第 36 行
Serializer serializer = new BinarySerializer();  // ← 又硬编码！

// 5 个测试文件里各写了一遍 new BinarySerializer()
```

问题：
1. 想从 Binary 切换到 JSON，改 6 个文件
2. 新增 Kryo 序列化器后，想用 Kryo 还得再改 6 处
3. 无法通过配置文件动态切换

### 目标

```java
// 一行代码，自动发现 classpath 上所有 Serializer 实现
Serializer serializer = SerializerFactory.getSerializer("json");
// 或取默认
Serializer serializer = SerializerFactory.getDefault();
```

---

## 二、业界参考：Dubbo SPI

### 2.1 Java 原生 SPI (ServiceLoader)

Java 自带 SPI 机制，但很弱：

```java
// JDK ServiceLoader 的问题：
// 1. 必须全量加载所有实现（不能按 key 精确获取）
// 2. 没有默认实现的概念
// 3. 没有缓存，每次都重新加载
ServiceLoader<Serializer> loader = ServiceLoader.load(Serializer.class);
for (Serializer s : loader) { ... }  // ← 只能遍历
```

### 2.2 Dubbo SPI 的改进

Dubbo 扩展了 SPI，核心改进：

```
Dubbo SPI 比 JDK ServiceLoader 强在哪？

① 按 key 获取：getExtension("json") 而非遍历所有
② @SPI 注解：标记默认实现（如 @SPI("json")）
③ 实例缓存：同一个实现类只创建一个实例
④ 配置文件格式：key=class 而不是单纯的 class 全限定名
```

**Dubbo 配置文件示例**（`META-INF/dubbo/org.apache.dubbo.remoting.Transporter`）：

```
netty=org.apache.dubbo.remoting.transport.netty4.NettyTransporter
mina=org.apache.dubbo.remoting.transport.mina.MinaTransporter
```

**Dubbo 使用代码**：

```java
// 获取指定实现
Transporter t = ExtensionLoader.getExtensionLoader(Transporter.class)
                               .getExtension("netty");

// 获取默认实现（@SPI("netty") 注解标记的）
Transporter t = ExtensionLoader.getExtensionLoader(Transporter.class)
                               .getDefaultExtension();
```

### 2.3 我们要实现的简化版

对标 Dubbo 的核心能力，但去掉高级特性（AOP 包装、依赖注入、@Activate 自动激活）：

| 特性 | Dubbo | HeteroMesh（本课） |
|------|-------|-------------------|
| 按 key 获取 | ✅ `getExtension("json")` | ✅ |
| @SPI 默认值 | ✅ `@SPI("json")` | ✅ |
| 实例缓存 | ✅ | ✅ |
| 配置文件 | `META-INF/dubbo/` | `META-INF/services/` |
| AOP 包装 | ✅ | ❌ 不做 |
| 依赖注入 | ✅ | ❌ 不做 |

---

## 三、你要写的代码

### 3.1 @SPI 注解

```java
package com.heteromesh.spi;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SPI {
    String value() default "";  // 默认实现的名字，如 "json"
}
```

用在哪：
```java
@SPI("json")                    // ← 默认用 json
public interface Serializer {
    byte[] serialize(Message message);
    Message deserialize(byte[] data);
    String name();
}
```

### 3.2 SpiExtensionLoader<T>（核心，~80 行）

泛型加载器，核心逻辑：

```
getExtension("json") 的执行流程：

1. 检查缓存 → 有就直接返回
2. 读取 META-INF/services/com.heteromesh.serializer.Serializer
3. 解析每一行：json=com.heteromesh.serializer.JsonSerializer
4. 找到 key="json" 这一行
5. 反射创建实例：Class.forName("com.heteromesh.serializer.JsonSerializer").newInstance()
6. 放入缓存
7. 返回
```

骨架：

```java
package com.heteromesh.spi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SpiExtensionLoader<T> {

    private final Class<T> type;                          // Serializer.class
    private final Map<String, T> cache = new ConcurrentHashMap<>();  // key → 实例
    private volatile String defaultName;                  // @SPI 注解的值

    private SpiExtensionLoader(Class<T> type) {
        this.type = type;
        // 解析 @SPI 注解，拿到默认名字
        SPI spi = type.getAnnotation(SPI.class);
        if (spi != null && !spi.value().isEmpty()) {
            this.defaultName = spi.value();
        }
    }

    // 静态工厂方法
    public static <T> SpiExtensionLoader<T> load(Class<T> type) {
        return new SpiExtensionLoader<>(type);
    }

    // 按 key 获取
    public T getExtension(String name) {
        // 1. 检查缓存
        // 2. 加载配置（只加载一次）
        // 3. 实例化 + 缓存
        // 4. 返回
    }

    // 获取默认实现
    public T getDefaultExtension() {
        if (defaultName == null) throw new IllegalStateException(...);
        return getExtension(defaultName);
    }

    // 加载 META-INF/services/<接口全限定名>
    private void loadExtensions() {
        String fileName = "META-INF/services/" + type.getName();
        // 用 ClassLoader 读取文件
        // 解析 key=value 格式
        // 反射实例化
    }
}
```

### 3.3 SerializerFactory（薄封装，~15 行）

```java
package com.heteromesh.serializer;

import com.heteromesh.spi.SpiExtensionLoader;

public class SerializerFactory {

    private static final SpiExtensionLoader<Serializer> LOADER =
            SpiExtensionLoader.load(Serializer.class);

    public static Serializer getSerializer(String name) {
        return LOADER.getExtension(name);
    }

    public static Serializer getDefault() {
        return LOADER.getDefaultExtension();
    }
}
```

### 3.4 SerializerType 枚举（可选）

```java
package com.heteromesh.serializer;

public enum SerializerType {
    JSON, BINARY, KRYO
}
```

### 3.5 META-INF/services 配置文件

位置：`heteromesh-common/src/main/resources/META-INF/services/com.heteromesh.serializer.Serializer`

内容：
```
json=com.heteromesh.serializer.JsonSerializer
binary=com.heteromesh.serializer.BinarySerializer
```

### 3.6 给 Serializer 接口加 @SPI 注解

```java
@SPI("json")
public interface Serializer { ... }
```

### 3.7 改造现有代码

把 `new JsonSerializer()` / `new BinarySerializer()` 替换为 `SerializerFactory.getDefault()` 或 `SerializerFactory.getSerializer("binary")`。

涉及文件：
- `HeteroMeshServer.java`
- `WorkerClient.java`
- `StressTest.java`
- `RpcIntegrationTest.java`
- `ClientServerIntegrationTest.java`
- `HeartbeatIntegrationTest.java`

---

## 四、测试文件（我来写）

### SpiExtensionLoaderTest.java

测试点：
1. 按 key 获取实现
2. 获取默认实现
3. 同一个 key 返回同一实例（缓存验证）
4. 获取不存在的 key 抛异常
5. 无 @SPI 注解时 getDefault 抛异常

### SerializerFactoryTest.java

测试点：
1. `getSerializer("json")` 返回 JsonSerializer
2. `getSerializer("binary")` 返回 BinarySerializer
3. `getDefault()` 返回 @SPI 指定的实现
4. serialize/deserialize 往返验证

---

## 五、你写完后怎么验证

```bash
mvn clean test
```

所有测试通过即完成。关键是：
- `SerializerFactory.getSerializer("json")` 能自动找到 JsonSerializer
- `SerializerFactory.getSerializer("binary")` 能自动找到 BinarySerializer
- 6 个现有文件中的 `new XxxSerializer()` 全部消失

---

## 六、面试口述稿

> "序列化器的发现机制参考了 Dubbo 的 SPI 设计。JDK 原生的 ServiceLoader 只能全量遍历，不能按名称精确获取，也没有默认实现的概念。我实现了一个 SpiExtensionLoader，支持 @SPI 注解标记默认实现，按 key 从 META-INF/services 配置文件加载，带缓存避免重复实例化。新增序列化方案时，只需写一个实现类并在配置文件加一行，不用改任何调用代码。这就是开闭原则——对扩展开放，对修改关闭。"

---

## 七、新包结构预览

```
com.heteromesh/
├── protocol/         (不变)
├── serializer/
│   ├── Serializer.java          ← 加 @SPI("json")
│   ├── JsonSerializer.java
│   ├── BinarySerializer.java
│   ├── SerializerFactory.java   ← 新建
│   └── SerializerType.java      ← 新建（可选）
├── spi/                         ← 新建包
│   ├── SPI.java                 ← @SPI 注解
│   └── SpiExtensionLoader.java  ← 核心加载器
├── registry/         (不变)
└── transport/        (不变)
```

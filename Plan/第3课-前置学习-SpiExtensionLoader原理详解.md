# SpiExtensionLoader 原理详解

---

## 一、先有痛点，再有方案

### 1.1 你现在面对的问题

你要用「序列化器」，但它有多个实现（JSON / Binary / 未来的 Kryo）。你现在的做法是：

```java
Serializer s = new BinarySerializer();  // 写死在代码里
```

这带来三个问题：

1. **换实现要改源码**：想从 Binary 换成 JSON？改 12 处。
2. **新增实现要改源码**：加了 KryoSerializer？还是改 12 处。
3. **无法动态切换**：比如通过配置文件 `serializer.type=json` 来选。

### 1.2 你真正想要什么

```java
// 一行代码，自动找到 classpath 上所有 Serializer 实现
// 按名字取出想要的
Serializer s = SerializerFactory.getSerializer("json");

// 或取默认的（比如 @SPI("json") 标记的那个）
Serializer s = SerializerFactory.getDefault();
```

这就是 **SPI（Service Provider Interface）** 的思想：

```
你只依赖接口 (Serializer)
    ↓
运行时自动发现实现类 (JsonSerializer / BinarySerializer)
    ↓
通过配置文件告诉系统「哪个 key 对应哪个类」
```

---

## 二、Java 原生的 SPI（JDK ServiceLoader）

### 2.1 怎么用

Java 其实自带了一个 SPI 机制：`java.util.ServiceLoader`。

**步骤 1**：在 `META-INF/services/` 下建一个文件，文件名是接口的全限定名：

```
META-INF/services/com.heteromesh.serializer.Serializer
```

**步骤 2**：文件内容写实现类的全限定名（一行一个）：

```
com.heteromesh.serializer.JsonSerializer
com.heteromesh.serializer.BinarySerializer
```

**步骤 3**：代码中使用：

```java
ServiceLoader<Serializer> loader = ServiceLoader.load(Serializer.class);
for (Serializer s : loader) {
    System.out.println(s.name());  // 输出 "json" → "binary"（顺序不保证）
}
```

### 2.2 致命缺陷

JDK 的 ServiceLoader 有三个问题，让它不适合做 RPC 框架的插件机制：

#### 缺陷 ①：只能遍历，不能按 key 精确获取

```java
// ❌ JDK ServiceLoader：我想取 "json" 这个实现？只能遍历全部然后自己判断
for (Serializer s : loader) {
    if ("json".equals(s.name())) {
        return s;  // ← 太蠢了，每次都遍历
    }
}

// ✅ 我们想要的：
getExtension("json");  // 直接拿到，O(1)
```

#### 缺陷 ②：没有「默认实现」的概念

```java
// JDK ServiceLoader 没办法标记"哪个是默认的"
// 我们想要的：
@SPI("json")           // ← 标记接口的默认实现是 json
public interface Serializer { ... }

getDefaultExtension(); // ← 自动返回 @SPI 指定的那个
```

#### 缺陷 ③：没有缓存，每次调用都重新创建实例

```java
// ServiceLoader 每次 reload() 都创建新对象
// 我们想要单例缓存：同一个实现类在全局只有一个实例
```

### 2.3 总结对比

| 特性 | JDK ServiceLoader | 我们需要的 |
|------|-------------------|-----------|
| 按 key 精确获取 | ❌ 只能遍历 | ✅ `getExtension("json")` |
| 默认实现 | ❌ 没有 | ✅ `@SPI("json")` |
| 实例缓存 | ❌ 无缓存 | ✅ `ConcurrentHashMap` |
| 配置文件格式 | 全限定类名 | `key=全限定类名`（Dubbo 格式） |

---

## 三、Dubbo 是怎么做的（我们的参考对象）

> Dubbo 的 `ExtensionLoader` 是整个框架的基石，2000+ 行代码。
> 我们只取它最核心的 20%，实现一个 ~80 行的简化版。

### 3.1 Dubbo SPI 的核心设计

Dubbo 对 JDK SPI 做了三个关键改进：

```
改进 1：配置文件支持 key=value 格式
──────────────────────────────────────
JDK:  com.heteromesh.serializer.JsonSerializer         ← 只有类名
Dubbo: json=com.heteromesh.serializer.JsonSerializer   ← key=类名，可按 key 查找


改进 2：@SPI 注解标记默认值
──────────────────────────────────────
@SPI("json")
public interface Serializer { ... }
// 当调用 getDefaultExtension() 时，自动取 "json"


改进 3：实例缓存
──────────────────────────────────────
Map<String, Serializer> cache = new ConcurrentHashMap<>();
// 第一次 getExtension("json") 时创建实例并放入缓存
// 以后再要 "json"，直接从缓存返回（单例）
```

### 3.2 Dubbo 配置文件长什么样

以 Dubbo 的实际配置为例（`META-INF/dubbo/internal/org.apache.dubbo.remoting.Transporter`）：

```
netty=org.apache.dubbo.remoting.transport.netty4.NettyTransporter
mina=org.apache.dubbo.remoting.transport.mina.MinaTransporter
grizzly=org.apache.dubbo.remoting.transport.grizzly.GrizzlyTransporter
```

使用时：

```java
// 拿到 netty 传输层实现
Transporter t = ExtensionLoader.getExtensionLoader(Transporter.class)
                               .getExtension("netty");

// 拿到默认的（@SPI("netty") 标注）
Transporter t = ExtensionLoader.getExtensionLoader(Transporter.class)
                               .getDefaultExtension();
```

### 3.3 我们做的简化

| 特性 | Dubbo ExtensionLoader | 我们的 SpiExtensionLoader |
|------|----------------------|--------------------------|
| 代码量 | ~2000 行 | ~80 行 |
| 按 key 获取 | ✅ | ✅ |
| @SPI 默认值 | ✅ | ✅ |
| 实例缓存 | ✅ | ✅ |
| AOP 包装类 | ✅（Wrapper 自动包装） | ❌ 不做 |
| 依赖注入 | ✅（@Inject 注入） | ❌ 不做 |
| 自动激活 | ✅（@Activate 注解） | ❌ 不做 |
| IOC 容器集成 | ✅（与 Spring 集成） | ❌ 不做 |

---

## 四、核心概念逐个讲

在写 SpiExtensionLoader 之前，你需要先理解四个基础知识。

### 4.1 `Class<T>` — 类的"身份证"

```java
// 每个 Java 类/接口，在 JVM 里都有一个 Class 对象
// 你可以把它理解成「类的身份证」

Class<String>  c1 = String.class;    // String 的身份证
Class<Serializer> c2 = Serializer.class;  // Serializer 接口的身份证

// 有了身份证，你可以：
c2.getName();                // → "com.heteromesh.serializer.Serializer"（全限定名）
c2.getAnnotation(SPI.class); // → 读取 @SPI 注解，拿到 "json"
c2.isInterface();            // → true（确实是接口）
```

**为什么重要**：`SpiExtensionLoader` 需要知道：
- 文件名：`"META-INF/services/" + type.getName()` → 拼接出配置文件路径
- 默认值：`type.getAnnotation(SPI.class).value()` → 读取 `@SPI` 注解

### 4.2 ClassLoader — 从 classpath 读取文件

**什么是 classpath？**

```
项目编译后：
 src/main/java/  ───编译───→  target/classes/
 src/main/resources/ ───复制───→  target/classes/

 最终 target/classes/ 就是 classpath 的根
 你放进去的任何文件都可以通过 ClassLoader 读取
```

**怎么读？**

```java
// 用「类加载器」读取 classpath 上的资源文件

ClassLoader cl = SpiExtensionLoader.class.getClassLoader();

// getResource：找一个文件（返回第一个找到的）
URL url = cl.getResource("META-INF/services/com.heteromesh.serializer.Serializer");

// getResources：找所有同名文件（返回全部）
// 为什么是复数？因为可能存在多个 jar 包都有这个文件，合并加载
Enumeration<URL> urls = cl.getResources("META-INF/services/com.heteromesh.serializer.Serializer");

while (urls.hasMoreElements()) {
    URL u = urls.nextElement();
    // 用 try-with-resources 自动关流
    try (BufferedReader reader = new BufferedReader(
             new InputStreamReader(u.openStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
            // 处理每一行：json=com.heteromesh.serializer.JsonSerializer
        }
    }
}
```

**`getResource` vs `getResources`?** 参考 Dubbo：当多个模块（如 `heteromesh-common`、未来可能的 `heteromesh-extensions`）都有 SPI 配置文件时，`getResources` 能全部找到并合并加载。Dubbo 正是这样支持第三方扩展的。

### 4.3 反射 — 从字符串创建对象

```java
// 正常创建对象（编译期就知道类型）：
Serializer s = new JsonSerializer();

// 反射创建对象（运行时才知道类型）：
String className = "com.heteromesh.serializer.JsonSerializer";  // 从配置文件读到
Class<?> clazz = Class.forName(className);                      // 通过字符串找到类的身份证
Serializer s = (Serializer) clazz.getDeclaredConstructor()     // 调用无参构造方法
                                  .newInstance();               // 创建实例

// 等于 new JsonSerializer()
```

**为什么用反射？** 因为 SPI 配置文件里存的是字符串 `"com.heteromesh.serializer.JsonSerializer"`，编译时根本不知道这个类存在，只能通过反射在运行时加载。

**安全的反射写法**（Java 9+）：

```java
// Java 8（已过时，但很多教程还在用）：
clazz.newInstance();

// Java 11+（推荐）：
clazz.getDeclaredConstructor().newInstance();
```

### 4.4 ConcurrentHashMap — 线程安全的缓存

```java
// 为什么不用 HashMap？
// HashMap 不是线程安全的，多线程同时 put 会数据错乱甚至死循环

// ConcurrentHashMap：线程安全，支持并发读写
Map<String, Serializer> cache = new ConcurrentHashMap<>();

cache.put("json", new JsonSerializer());   // 放入缓存
Serializer s = cache.get("json");          // 从缓存取，O(1)

// 第一次调用 getExtension("json") → 创建实例，放入缓存
// 之后再调用 getExtension("json") → 直接从缓存返回（单例）
```

`volatile` 关键字的作用：

```java
private volatile boolean loaded = false;

// volatile 保证：
// 线程 A 把 loaded 改成 true 后
// 线程 B 立刻就能看到这个变化
// 不会出现「线程 A 加载完了，线程 B 又加载一次」的问题
```

---

## 五、SpiExtensionLoader 完整执行流程

### 5.1 初始化阶段

```java
// 你写：
SpiExtensionLoader<Serializer> loader = SpiExtensionLoader.load(Serializer.class);

// 内部做了什么：
// 1. 拿到 Serializer.class 身份证
// 2. 读取 @SPI("json") 注解 → defaultName = "json"
// 3. 初始化一个空的 ConcurrentHashMap（还没加载实例！）
```

### 5.2 首次调用 `getExtension("binary")` 时

```
getExtension("binary")
  │
  ├─ step 1. 检查缓存：cache.get("binary") → null（cache 是空的）
  │
  ├─ step 2. 检查 loaded 标记 → false（还没加载过）
  │      │
  │      ├─ synchronized (this) {        ← 加锁，防止多线程同时加载
  │      │     if (!loaded) {            ← 双重检查，可能别的线程已经加载了
  │      │        loadExtensions();      ← 真正加载
  │      │        loaded = true;
  │      │     }
  │      │  }
  │      │
  │      └─ loadExtensions() 做了什么？
  │           ├─ 拼接文件名："META-INF/services/com.heteromesh.serializer.Serializer"
  │           ├─ 用 ClassLoader 读取这个文件
  │           ├─ 逐行解析：
  │           │    "json=com.heteromesh.serializer.JsonSerializer"
  │           │    "binary=com.heteromesh.serializer.BinarySerializer"
  │           ├─ 对每一行：
  │           │    Class.forName("com.heteromesh.serializer.JsonSerializer")
  │           │    → getDeclaredConstructor().newInstance()
  │           │    → cache.put("json", instance)
  │           │
  │           │    Class.forName("com.heteromesh.serializer.BinarySerializer")
  │           │    → getDeclaredConstructor().newInstance()
  │           │    → cache.put("binary", instance)
  │           └─ cache 现在有 2 个条目
  │
  └─ step 3. cache.get("binary") → BinarySerializer 实例 ✓
```

### 5.3 第二次调用 `getExtension("binary")` 时

```
getExtension("binary")
  │
  ├─ 检查缓存：cache.get("binary") → BinarySerializer 实例（命中！）
  │
  └─ 直接返回（不走 loadExtensions，loaded 已经是 true）
```

### 5.4 可视化流程图

```
                    ┌──────────────────────────────┐
                    │ getExtension("binary")        │
                    └──────────────┬───────────────┘
                                   │
                          ┌────────▼────────┐
                          │ 缓存命中？        │
                          └────┬───────┬────┘
                               │YES    │NO
                               ▼       ▼
                          ┌────────┐ ┌──────────────┐
                          │ 返回   │ │ loaded==true? │
                          └────────┘ └──┬───────┬───┘
                                        │YES    │NO
                                        ▼       ▼
                              ┌─────────────┐ ┌──────────────────┐
                              │ 查缓存，     │ │ synchronized {    │
                              │ 有则返回，   │ │   if(!loaded) {   │
                              │ 无则抛异常   │ │     loadExtensions│
                              └─────────────┘ │     loaded=true   │
                                              │   }               │
                                              │ }                 │
                                              └──────────────────┘
```

---

## 六、为什么这样设计：四个设计决策

### 决策 1：为什么要双重检查锁，而不是直接 synchronized 方法？

```java
// ❌ 简单但慢：每次调用都加锁
public synchronized T getExtension(String name) {
    // 即使缓存已有数据，还是要排队等锁
}

// ✅ 双重检查锁：只在第一次加载时加锁
// 99.9% 的调用直接从缓存返回，不加锁，性能好
public T getExtension(String name) {
    T cached = cache.get(name);
    if (cached != null) return cached;  // ← 快速路径，不加锁

    if (!loaded) {
        synchronized (this) {
            if (!loaded) {  // ← 双重检查（另一个线程可能刚好加载完）
                loadExtensions();
                loaded = true;
            }
        }
    }
    return cache.get(name);
}
```

**参考来源**：单例模式的双重检查锁定（Effective Java 第 83 条），Dubbo 的 `loadExtensionClasses()` 也是类似设计。

### 决策 2：为什么缓存用 ConcurrentHashMap 而不是 HashMap？

```
ConcurrentHashMap：允许多个线程同时读取，不用排队
HashMap：多线程同时 put 可能死循环（JDK 7）或数据丢失

SpiExtensionLoader 可能在多线程环境下被调用
→ 必须用 ConcurrentHashMap（Dubbo 也是）
```

### 决策 3：为什么要支持 `#` 注释和空行？

```java
line = line.trim();
if (line.isEmpty() || line.startsWith("#")) {
    continue;
}
```

参考 Dubbo 的配置文件，允许注释方便维护：

```
# 默认 JSON 序列化
json=com.heteromesh.serializer.JsonSerializer

# 高性能二进制序列化（心跳用）
binary=com.heteromesh.serializer.BinarySerializer
```

### 决策 4：为什么用 `getResources` 而不是 `getResource`？

```
getResource()：只返回classpath上找到的第一个匹配文件
getResources()：返回所有jar包中的同名文件

后者允许模块化：将来 heteromesh-extensions 模块可以提供
自己的 META-INF/services/com.heteromesh.serializer.Serializer，
里面写 kryo=...，加载器会自动合并。

Dubbo 正是这样实现"引入 jar 包即可自动发现扩展"的。
```

---

## 七、你接下来要写的三个文件

### 文件 1：SPI 注解（~10 行）✅ 已完成

### 文件 2：SpiExtensionLoader（~80 行）← 现在写这个

核心就是这个类，理解了上面全部内容后写它就是「翻译成 Java 代码」。

### 文件 3：SerializerFactory（~15 行）

薄封装，把 `SpiExtensionLoader.load(Serializer.class)` 包一层，让你使用更方便。

---

## 八、总结

SpiExtensionLoader 本质上就做了三件事：

```
1. 找文件：通过 ClassLoader 读取 META-INF/services/<接口名>
2. 读配置：逐行解析 key=value，得到 (key, 全限定类名) 的映射
3. 反射建：Class.forName() → getDeclaredConstructor().newInstance() → 放入缓存
```

整个设计的思想来源：

```
Dubbo ExtensionLoader（2000行）
    ↓ 取其核心 20%
我们的 SpiExtensionLoader（80行）
    ↓ 薄封装
SerializerFactory（15行）
```

> "好的框架设计不是凭空创造的，而是站在巨人肩膀上，理解核心原理后做减法。" — Dubbo 作者 梁飞

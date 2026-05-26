# SPI 可插拔架构详解

---

## 一、SPI 是什么？为什么需要它？

### 1.1 一句话定义

> **SPI（Service Provider Interface）= 「接口 + 配置文件」驱动的插件发现机制。**
> 框架只定义接口，具体实现由配置文件声明，运行时自动发现和加载。

### 1.2 不用 SPI 会怎样？

```java
// ❌ 硬编码：想换实现？改代码 → 重新编译 → 重启
Serializer serializer = new JsonSerializer();   // 写死了，改不了

// ❌ if-else 大法：每加一个新实现，这里多一个分支
if ("json".equals(type)) {
    serializer = new JsonSerializer();
} else if ("binary".equals(type)) {
    serializer = new BinarySerializer();
} else if ("protobuf".equals(type)) {           // ← 新加的，又要改代码
    serializer = new ProtobufSerializer();
}

// ✅ SPI：加插件不改代码，配置文件一行搞定
Serializer serializer = SerializerFactory.getSerializer("protobuf");
```

### 1.3 核心价值

```
                   不加 SPI                    加了 SPI
─────────────────────────────────────────────────────────────
切换实现      改代码 + 重新编译 + 重启        改配置文件 + 重启
新增实现      改 Factory 类 + 重新编译         写实现类 + 注册一行配置
第三方扩展    必须改源码                       打 jar 丢进 classpath 就自动发现
开闭原则      对扩展开放 ❌ 对修改封闭 ❌       对扩展开放 ✅ 对修改封闭 ✅
```

---

## 二、HeteroMesh 的 SPI 三件套

HeteroMesh 的 SPI 机制由三个核心组件构成：

```
┌──────────────────────────────────────────────────┐
│                   @SPI 注解                        │
│  标记接口是 SPI 扩展点，指定默认实现名称              │
│  @SPI("json")                                    │
│  public interface Serializer { ... }              │
└──────────────────┬───────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────┐
│               SpiExtensionLoader<T>               │
│  核心引擎：读配置文件 → 反射创建实例 → 缓存          │
│  - load(Class<T>)   : 创建加载器                   │
│  - getExtension(n)  : 按名称获取实现                │
│  - getDefault()     : 获取默认实现                  │
└──────────────────┬───────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────┐
│         META-INF/services/<接口全限定名>             │
│  SPI 配置文件，声明有哪些实现                        │
│  json=com.heteromesh.serializer.JsonSerializer    │
│  binary=com.heteromesh.serializer.BinarySerializer │
└──────────────────────────────────────────────────┘
```

### 2.1 @SPI 注解

```java
@Documented
@Retention(RetentionPolicy.RUNTIME)  // ← 运行时通过反射读取
@Target(ElementType.TYPE)            // ← 只能放在接口/类上
public @interface SPI {
    String value() default "";       // 默认实现的名字
}
```

**作用：** 两件事——
1. 标记：这个接口是 SPI 扩展点（一眼就知道它是可插拔的）
2. 指定默认值：`@SPI("json")` → 不传名字时用 "json" 这个实现

### 2.2 SpiExtensionLoader——核心引擎

```java
public class SpiExtensionLoader<T> {
    private final Class<T> type;                    // 接口类型，如 Serializer.class
    private final Map<String, T> cache;             // 实例缓存，key=名称
    private volatile String defaultName;            // @SPI 注解上写的默认值
    private volatile boolean loaded = false;        // 是否已经加载过配置文件

    // 构造器是私有的，只能通过静态工厂创建
    public static <T> SpiExtensionLoader<T> load(Class<T> type) { ... }

    // 按名称取实现（首次调用时触发加载）
    public T getExtension(String name) { ... }

    // 取默认实现
    public T getDefault() { ... }

    // 一次性加载：读 META-INF/services 下的配置文件
    private void loadExtensions() { ... }
}
```

**加载流程（完整链路）：**

```
getExtension("json")
  │
  ├─ ① 查缓存 cache.get("json")
  │    └─ 命中 → 直接返回（后续调用走这条，O(1)）
  │
  ├─ ② 未命中 → 检查 loaded 标志
  │    └─ false → 进入同步块（双重检查）
  │         └─ 还是 false → loadExtensions()
  │
  └─ ③ loadExtensions()
       │
       ├─ 构建文件路径: "META-INF/services/com.heteromesh.serializer.Serializer"
       │
       ├─ ClassLoader.getResources() 找到这个文件
       │
       ├─ 逐行解析:
       │   json=com.heteromesh.serializer.JsonSerializer
       │   binary=com.heteromesh.serializer.BinarySerializer
       │   跳过空行和 # 注释行
       │
       ├─ 反射创建实例:
       │   Class.forName("com.heteromesh.serializer.JsonSerializer")
       │        .getDeclaredConstructor()
       │        .newInstance()
       │
       └─ 放入缓存: cache.put("json", instance)
```

**关键设计点：**

| 设计 | 原因 |
|------|------|
| `volatile + synchronized` 双重检查 | 多线程安全 + 只加载一次，后续调用无锁 |
| `ConcurrentHashMap` 缓存 | 加载后每次 getExtension 是 O(1) 无锁读 |
| `key=className` 格式 | JDK SPI 只支持类名列表，不支持按名称查找；key-value 格式给每个实现起一个别名 |
| 支持 `#` 注释 | 配置文件可读性更好 |

### 2.3 SPI 配置文件

位置必须是 `META-INF/services/<接口全限定名>`，这是 Java SPI 规范约定的路径。

```properties
# 格式：名称=全限定类名
# 支持 # 注释和空行
json=com.heteromesh.serializer.JsonSerializer
binary=com.heteromesh.serializer.BinarySerializer
```

---

## 三、Serializer——第一个 SPI 化的模块

Serializer 是目前项目中 SPI 机制最完整的应用案例，也是 LoadBalancer SPI 化的模板。

### 3.1 完整调用链

```
application.yml                      @SPI("json")
     │                                    │
     ▼                                    ▼
SerializationConfig              Serializer 接口
  "TASK_REQUEST → json"                 │
     │                                    ▼
     ▼                           SpiExtensionLoader<Serializer>
SerializerRouter                        │
  ├─ CODE_MAP: {JSON→JsonSerializer,     ▼
  │             BINARY→BinarySerializer} META-INF/services/
  └─ TYPE_MAP: {TASK_REQUEST→JSON,       com.heteromesh.serializer.Serializer
                 PING→BINARY, ...}           │
     │                                       ▼
     ├─ select(msg): 发消息时选序列化器    实现类:
     ├─ getByCode(b): 收消息时解码         JsonSerializer
     └─ getCode(msg): 写协议头             BinarySerializer
```

### 3.2 各层职责

```
层                    职责                          类比
──────────────────────────────────────────────────────────
@SPI("json")         声明 SPI 扩展点 + 默认实现        「招聘岗位描述」
Serializer 接口       定义序列化契约                   「岗位要求」
SpiExtensionLoader    发现 + 加载 + 缓存实现           「HR 筛简历」
SerializerFactory     封装 SpiExtensionLoader          「HR 热线电话」
META-INF/services/    列出所有候选人                   「简历库」
SerializerRouter      按消息类型智能分派               「排班表」
application.yml       声明分派规则                     「排班规则」
```

### 3.3 为什么 Serializer 需要 Router 而 LoadBalancer 不需要？

```
Serializer：不同消息类型 → 不同序列化器
  PING       → binary（心跳，紧凑优先）
  TASK_REQUEST → json（业务，可读优先）
  → 需要一个 Router 做「按消息类型 → 选序列化器」的映射
  
LoadBalancer：整个集群 → 一个负载均衡策略
  所有 TASK_REQUEST 都用同一个策略
  → 不需要 Router，一个 Factory 就够
```

---

## 四、JDK SPI vs Dubbo SPI vs HeteroMesh SPI

| 特性 | JDK SPI | Dubbo SPI | HeteroMesh SPI |
|------|---------|-----------|----------------|
| 配置文件路径 | `META-INF/services/` | `META-INF/dubbo/` | `META-INF/services/` |
| 配置格式 | 纯类名列表 | key=className | key=className |
| 按名称查找 | 不支持（必须遍历全部） | 支持 | 支持 |
| 默认实现 | 不支持 | 支持（@SPI 注解） | 支持（@SPI 注解） |
| 实例缓存 | 不支持（每次新建） | 支持（单例缓存） | 支持（单例缓存） |
| AOP/IOC | 不支持 | 支持（Wrapper/Inject） | 不支持（保持简单） |
| 依赖 | JDK 自带 | dubbo-common | 自己写 |

**为什么不用 JDK 原生 SPI？**

```java
// JDK SPI：必须遍历所有实现才能找到想要的
ServiceLoader<Serializer> loader = ServiceLoader.load(Serializer.class);
for (Serializer s : loader) {          // ← 全部实例化一遍
    if ("binary".equals(s.name())) {   // ← 再按名称过滤
        return s;
    }
}
// → 不能按名称直接获取（因为配置文件里没有 key）
// → 不支持默认实现
// → 每次调用都重新加载（没有缓存）

// HeteroMesh SPI：按名称直接拿
Serializer s = SerializerFactory.getSerializer("binary");  // 一步到位
```

**为什么不用 Dubbo SPI？**

我们是教学项目，核心目标是「理解原理」。引入 Dubbo 依赖就变成「用 Dubbo SPI 实现 SPI 化」——绕过了最核心的 SpiExtensionLoader，学不到东西。自己写一个精简版，100 行代码把 SPI 的加载、反射、缓存、双重检查锁全串起来，比看 Dubbo 源码高效得多。

---

## 五、LoadBalancer SPI 化——完全复刻 Serializer 模式

### 5.1 对照表

```
Serializer 模块              →   LoadBalancer 模块
─────────────────────────────────────────────────────
@SPI("json")                →   @SPI("consistentHash")
Serializer 接口              →   LoadBalancer 接口
JsonSerializer              →   ConsistentHashLoadBalancer
BinarySerializer            →   RandomLoadBalancer
                            →   RoundRobinLoadBalancer
                            →   WeightedLoadBalancer
SerializerFactory           →   LoadBalancerFactory
SerializerRouter            →   （不需要 Router，一个全局策略就够了）
SerializationConfig         →   （直接从 ConfigLoader 读，不需要专门的 Config 类）
```

### 5.2 Factory 代码对比

```java
// SerializerFactory
public class SerializerFactory {
    private static final SpiExtensionLoader<Serializer> LOADER =
            SpiExtensionLoader.load(Serializer.class);
    public static Serializer getSerializer(String name) { return LOADER.getExtension(name); }
    public static Serializer getDefault() { return LOADER.getDefaultExtension(); }
}

// LoadBalancerFactory（几乎一模一样，只是类型和名字不同）
public class LoadBalancerFactory {
    private static final SpiExtensionLoader<LoadBalancer> LOADER =
            SpiExtensionLoader.load(LoadBalancer.class);
    public static LoadBalancer getLoadBalancer(String name) { return LOADER.getExtension(name); }
    public static LoadBalancer getDefault() { return LOADER.getDefaultExtension(); }
}
```

这个 Factory 类体量极小（18 行），因为所有重活都是 `SpiExtensionLoader` 干的。Factory 只是一个「便捷门面」，把泛型参数和静态字段封装起来，让调用方写 `LoadBalancerFactory.getDefault()` 而不是 `SpiExtensionLoader.load(LoadBalancer.class).getDefaultExtension()`。

---

## 六、如何新增一个 SPI 插件？（实战演练）

假设要加一个 Protobuf 序列化器：

### 只需要改 3 个文件，框架代码零修改

```
① 写实现类（新建）
   ProtobufSerializer.java
     implements Serializer
     name() → "protobuf"

② 注册到 SPI 配置文件（修改 1 行）
   META-INF/services/com.heteromesh.serializer.Serializer
     + protobuf=com.heteromesh.serializer.ProtobufSerializer

③ 修改路由规则（按需）
   application.yml:
     routing:
       TASK_REQUEST: protobuf   ← 把业务请求切到 protobuf

完成。
  - SerializerFactory 不用改
  - SerializerRouter 不用改
  - SpiExtensionLoader 不用改
  - 框架代码：0 行修改
```

这就是 SPI 的核心价值——**对扩展开放，对修改封闭**。

---

## 七、SpiExtensionLoader 的并发安全设计

```
                         多个线程同时调 getExtension("json")
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
              cache.get("json")  cache.get("json")  cache.get("json")
                    │               │               │
                    ▼               ▼               ▼
                 null             null             null
                    │               │               │
                    └───────────────┼───────────────┘
                                    │
                             loaded == false?
                                    │
                              synchronized(this) {     ← 只有第一个线程进去
                                  if (!loaded) {          ← 双重检查，后面的线程进来时 loaded 已经是 true
                                      loadExtensions();   ← 只执行一次
                                      loaded = true;
                                  }
                              }
                                    │
                              cache.get("json")  ← 一定能拿到
```

**为什么是双重检查锁（DCL）而不是直接在方法上加 `synchronized`？**

```
在方法上加 synchronized → 每次 getExtension 都要抢锁
  → 加载完成后，99.999% 的调用只是读缓存
  → 让这些读操作去抢锁完全是浪费

DCL → 只有第一次调用（加载时）需要锁
    → 后续调用走 cache.get() → ConcurrentHashMap 的无锁读
    → 高频路径零同步开销
```

---

## 八、关键面试问答

> **Q: 你们框架的 SPI 和 JDK 的 ServiceLoader 有什么区别？**
>
> A: JDK SPI 的配置文件是纯类名列表，只能遍历全部实现后按类型过滤，不支持按名称直接获取。我们用的是 key=className 格式，可以按名称 O(1) 拿到指定实现。另外 JDK SPI 没有默认实现和实例缓存的概念，每次都要重建。我们加了 `@SPI` 注解声明默认值，用 ConcurrentHashMap 做单例缓存，SpiExtensionLoader 双重检查锁保证只加载一次。

> **Q: 为什么自己写 SPI 而不是用 Dubbo 的？**
>
> A: 教学项目，目标是理解 SPI 原理而不是用现成的。自己写一遍 SpiExtensionLoader，把类加载、反射、双重检查锁、配置文件解析串起来，100 行代码比看 Dubbo 几千行的 SPI 源码学得透彻。而且我们的 SpiExtensionLoader 是项目级复用——Serializer 和 LoadBalancer 都在用，证明了这套机制确实做到了「写一次，到处用」。

> **Q: SpiExtensionLoader 的线程安全怎么保证的？**
>
> A: 三重保障——① `volatile loaded` 保证可见性 ② `synchronized` 保证只加载一次 ③ 双重检查避免后续调用的同步开销 ④ `ConcurrentHashMap` 缓存保证加载后的读操作完全无锁。这是标准的 DCL 模式，和 `ConfigLoader` 的设计一致。

> **Q: 如果要让第三方给你们的框架贡献插件，怎么做？**
>
> A: 他们只需要——① 在自己的项目里实现 `LoadBalancer` 接口 ② 在自己的 jar 里放 `META-INF/services/com.heteromesh.loadbalancer.LoadBalancer` 文件，写一行 `myStrategy=com.xxx.MyLoadBalancer` ③ 把 jar 丢进 classpath。我们框架代码一行不用改，SpiExtensionLoader 自动通过 `ClassLoader.getResources()` 发现他们的配置文件。
>
> 这就是 SPI 最核心的商业模式价值——框架开发者不需要知道所有实现，插件开发者不需要改框架源码。

---

## 九、一句话总结

> **SPI = 把「写死在代码里的 new」变成「写在配置文件里的 class name」。**
> **框架定义契约（接口），插件提供实现，SpiExtensionLoader 做媒人。**
>
> 这是所有现代框架（Dubbo、Spring Boot 自动配置、JDBC 驱动加载）的基因。
> 理解了 HeteroMesh 的 SPI，Dubbo 的 ExtensionLoader 不过是多了 AOP 和 IOC 的豪华版。

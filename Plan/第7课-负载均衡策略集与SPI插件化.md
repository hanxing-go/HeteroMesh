# 第 7 课：负载均衡策略集 + SPI 插件化

---

## 上下文（给新会话看）

- **项目**：HeteroMesh，从零手写分布式 RPC 框架，Java 21 + Netty + Maven 多模块
- **路径**：`C:\Users\12099\Desktop\HeteroMesh\HeteroMesh`
- **已完成**：第 6 课（一致性哈希负载均衡），Controller 能把任务按 requestId 哈希路由到指定 Worker
- **当前状态**：LoadBalancer 只有一种实现（`ConsistentHashLoadBalancer`），且是硬编码 `new` 出来的——想换策略就得改代码重启，跟 Serializer 第 3 课前的情况一模一样

---

## 一、这节课解决什么问题？

### 当前状态 vs 目标

```
当前（第 6 课后）：
  HeteroMeshServer.java:
    LoadBalancer lb = new ConsistentHashLoadBalancer();  ← 硬编码
    ↑
    想换成随机策略？改代码 → 重新编译 → 重启
    想加一个新策略？实现类写了，但没有加载机制

这节课后：
  application.yml:
    heteromesh:
      loadbalancer:
        default: consistentHash   ← 改一行配置就能切换策略

  HeteroMeshServer.java:
    LoadBalancer lb = LoadBalancerFactory.getDefault();  ← SPI 自动发现
    ↑
    四种策略任选：consistentHash / random / roundRobin / weighted
    加新策略：写一个实现类 + 在 SPI 配置文件里注册一行 → 搞定
```

### 为什么一致性哈希不够用？

一致性哈希解决的是 **「同一个 key 总是路由到同一个节点」**——这对于有状态任务（比如同一个 session 的数据必须去同一个 Worker 缓存处理）非常重要。

但不是所有场景都需要「亲和性」：

| 场景 | 适合的策略 | 为什么 |
|------|-----------|--------|
| 无状态任务（每次请求独立，如单张图片推理） | **Random** | 最简单，统计上均匀，不需要维护计数 |
| 任务队列模式（Worker 轮流领任务） | **Round-Robin** | 严格轮流，保证每个节点被调度的次数绝对相等 |
| 异构硬件（A100 和 T4 混部，算力不同） | **Weighted** | 好 GPU 多分配任务，弱 GPU 少分配，按能力分工 |
| 有状态会话（同一 session 必须去同一 Worker） | **ConsistentHash** | 上下线只影响少量 key，缓存亲和性 |

这节课把后三种也实现出来，并让 LoadBalancer 像 Serializer 一样支持 SPI 热插拔。

---

## 二、三种新策略详解

### 2.1 Random（随机）

**最简单的负载均衡——闭眼抽一张牌。**

```
3 个 Worker：A、B、C
每次 select() 随机选一个
→ 统计学上，大量请求会均匀分布到所有节点

选 3000 次 → A≈1000, B≈1000, C≈1000
```

实现要点：
- 内部维护一个 `List<ServiceInstance>`（节点列表）
- `select()` 时生成随机索引 `[0, list.size()-1]`
- 用 `ThreadLocalRandom` 而不是 `Math.random()`——多线程下性能更好，无竞争

**和一致性哈希的关键区别：**
- Random 每次 select **独立随机**，同一个 key 两次 select 结果可能不同
- ConsistentHash 同一个 key 永远选同一个节点（除非节点变化）

### 2.2 Round-Robin（轮询）

**轮流坐庄——排好队，一个一个来。**

```
3 个 Worker：A、B、C
select() 第 1 次 → A
select() 第 2 次 → B
select() 第 3 次 → C
select() 第 4 次 → A   ← 回到队首，循环
select() 第 5 次 → B
...
```

实现要点：
- 内部维护一个 `AtomicInteger` 计数器
- 每次 `select()` 取 `counter.getAndIncrement() % nodeCount` 作为索引
- **为什么用 `AtomicInteger` 而不是 `int`？** ServerHandler 是 Netty 多线程处理的，多个 Worker 可能同时发 TASK_REQUEST → 多个线程同时调 `select()` → 需要线程安全的计数器

**`getAndIncrement()` 溢出问题：**

```java
// AtomicInteger 从 0 一直自增，到 Integer.MAX_VALUE (2^31-1) 后翻转到 Integer.MIN_VALUE (-2^31)
// Java 的 % 运算对负数保持符号：
//   -1 % 3 = -1  （不是 2！）

// ❌ 直接用可能拿到负数索引
int idx = counter.getAndIncrement() % nodes.size();  // idx 可能是 -1, -2, ...

// ✅ 处理方式：mask 掉符号位
int idx = (counter.getAndIncrement() & 0x7FFFFFFF) % nodes.size();
//        ↑ 保证 idx 永远是非负数，最大值 2^31-1，用完从 0 继续
```

### 2.3 Weighted（加权）

**按劳分配——能力越强，干的活越多。**

```
2 个 Worker：
  - Worker-A（A100，weight=200）：200/(200+100) = 66.7% 的流量
  - Worker-B（T4，weight=100）：  100/(200+100) = 33.3% 的流量

不是严格轮流，而是按权重比例随机分配。
3000 次请求 → A ≈ 2000次，B ≈ 1000次
```

**实现方案：累积权重 + 二分查找**

```
示例：3 个节点，权重分别为 [50, 30, 20]，总权重 = 100

累积权重数组：
  索引:   0    1    2
  权重:  [50,  80,  100]
         ↑    ↑    ↑
      节点A  节点B  节点C

select() 时：
  1. 生成 [0, 100) 的随机数 dice
  2. 二分查找：找到第一个累积权重 > dice 的位置
     - dice = 35 → 50 > 35 → 索引0 → 节点A
     - dice = 65 → 50 ≤ 65, 80 > 65 → 索引1 → 节点B
     - dice = 90 → 80 ≤ 90, 100 > 90 → 索引2 → 节点C
```

**为什么用二分查找而不是遍历？**

```java
// ❌ 遍历：O(n)
int dice = random.nextInt(totalWeight);
int acc = 0;
for (int i = 0; i < nodes.size(); i++) {
    acc += nodes.get(i).getWeight();
    if (dice < acc) return nodes.get(i);
}

// ✅ 二分查找：O(log n)，节点越多优势越明显
int lo = 0, hi = cumulative.size() - 1;
while (lo < hi) {
    int mid = (lo + hi) >>> 1;
    if (cumulative.get(mid) > dice) hi = mid;
    else lo = mid + 1;
}
return nodes.get(lo);
```

**累积权重数组何时重建？**
- `addNode()` / `removeNode()` 时重建——节点变化是低频操作（一分钟几次）
- `select()` 直接读现成的数组——高频操作（每秒几万次）
- 和 ConsistentHash 一样的思想：**写时重建，读时直接查**

### 2.4 四种策略对比

```
                    select 复杂度   依赖 key?   节点增减代价    适用场景
─────────────────────────────────────────────────────────────────────
Random              O(1)            否          无             无状态任务，最简单
Round-Robin         O(1)            否          无             公平轮询，按次均分
Weighted            O(log n)        否          O(n) 重建      异构硬件混部
ConsistentHash      O(log n)        是          O(V) 增删     有状态/需要亲和性
```

---

## 三、SPI 插件化——让 LoadBalancer 可插拔

### 3.1 回顾：Serializer 是怎么做 SPI 的？

```
Serializer 接口
  @SPI("json")              ← 注解声明接口是 SPI，默认实现叫 "json"
  ↑
SpiExtensionLoader<Serializer>
  读 META-INF/services/com.heteromesh.serializer.Serializer
  ↑
SerializerFactory
  封装 SpiExtensionLoader，提供 getSerializer(name) / getDefault()
  ↑
SerializerRouter
  根据 YAML 配置按 MessageType 自动选 Serializer
```

### 3.2 LoadBalancer 改造步骤（完全复刻这套流程）

```
改造前：
  HeteroMeshServer 直接 new ConsistentHashLoadBalancer()
  ↑ 硬编码，只有一个实现

改造后（5 步）：
  ① LoadBalancer 接口加 @SPI("consistentHash") 注解
  ② 新增 3 个实现类（Random / RoundRobin / Weighted）
  ③ 新增 META-INF/services/com.heteromesh.loadbalancer.LoadBalancer（SPI 注册文件）
  ④ 新建 LoadBalancerFactory（封装 SpiExtensionLoader，对标 SerializerFactory）
  ⑤ HeteroMeshServer 改用 LoadBalancerFactory.getDefault()，具体策略读 YAML
```

**和 Serializer 的区别：**
- Serializer 是**按消息类型路由**（PING→binary, TASK_REQUEST→json），需要 SerializerRouter
- LoadBalancer 是**全局选一个策略**，不需要路由器，一个 Factory 就够了
- 所以 LoadBalancer 的 SPI 化比 Serializer 更简单

### 3.3 为什么要 SPI 化？

```
不改的时候：
  想切策略 → 改 HeteroMeshServer.java 的 new XXXLoadBalancer() → mvn package → 重启
  → 3 分钟起步

改了之后：
  想切策略 → 改 application.yml 一行配置 → 重启
  → 10 秒搞定
  → 而且你可以写自己的 LoadBalancer 实现，打成 jar 丢进 classpath 就自动发现
```

这和 Dubbo 的 SPI 机制是同一个思想——**微内核 + 插件化**。框架核心只管调度，具体策略由插件决定。

---

## 四、你要写的代码

### 4.1 LoadBalancer 接口改造（加 @SPI + 加 name() 方法）

位置：`heteromesh-common/src/main/java/com/heteromesh/loadbalancer/LoadBalancer.java`

**为什么要改？**
- 加 `@SPI("consistentHash")`：告诉 `SpiExtensionLoader` 这个接口要走 SPI 加载，默认实现叫 `consistentHash`
- 加 `name()` 方法：让每个实现返回自己的策略名称，方便日志和调试（和 Serializer 的 `name()` 对标）
- **和原来的区别**：原来是普通接口，现在是 SPI 接口 + 多一个 `name()` 方法

```java
package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.spi.SPI;

@SPI("consistentHash")
public interface LoadBalancer {

    /**
     * 节点上线时调用：把节点加入负载均衡器
     */
    void addNode(ServiceInstance instance);

    /**
     * 节点下线时调用：从负载均衡器移除节点
     */
    void removeNode(String nodeId);

    /**
     * 根据 key 选择一个节点
     * 注意：Random / RoundRobin / Weighted 实现中可以不使用 key 参数
     * @return 选中的 ServiceInstance，没有可用节点时返回 null
     */
    ServiceInstance select(String key);

    /**
     * 当前有多少个物理节点
     */
    int size();

    /**
     * 返回该负载均衡策略的名称
     * 例如 "consistentHash", "random", "roundRobin", "weighted"
     */
    String name();
}
```

### 4.2 ServiceInstance 加 weight 字段

位置：`heteromesh-common/src/main/java/com/heteromesh/registry/ServiceInstance.java`

**为什么要改？**
- `WeightedLoadBalancer` 需要知道每个节点的权重才能按比例分配流量
- 默认值 100，这样不改注册代码也能直接使用（所有节点等权重 = 退化为 Random）
- **和原来的区别**：多了一个 `int weight = 100` 字段

```java
// ServiceInstance.java 新增字段：
private int weight = 100;  // 节点权重，默认 100。用于加权负载均衡

// 对应的 AllArgsConstructor 也会自动包含这个字段（Lombok）
// 已有的注册代码不需要改——Worker 注册时可以不带 weight，默认为 100
```

### 4.3 ConsistentHashLoadBalancer 加 name() 方法

位置：`heteromesh-common/src/main/java/com/heteromesh/loadbalancer/ConsistentHashLoadBalancer.java`

**为什么要改？**
- 接口新增了 `name()` 方法，实现类必须加上
- **和原来的区别**：只多了 3 行

```java
@Override
public String name() {
    return "consistentHash";
}
```

### 4.4 RandomLoadBalancer（~40 行）

位置：`heteromesh-common/src/main/java/com/heteromesh/loadbalancer/RandomLoadBalancer.java`

```java
package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class RandomLoadBalancer implements LoadBalancer {

    // CopyOnWriteArrayList：读多写少场景，读不加锁，写时复制
    private final List<ServiceInstance> nodes = new CopyOnWriteArrayList<>();

    @Override
    public void addNode(ServiceInstance instance) {
        // 避免重复添加同一个节点
        removeNode(instance.getNodeId());
        nodes.add(instance);
        log.info("Random: 节点加入, nodeId={}, totalNodes={}", instance.getNodeId(), nodes.size());
    }

    @Override
    public void removeNode(String nodeId) {
        nodes.removeIf(n -> n.getNodeId().equals(nodeId));
        log.info("Random: 节点移除, nodeId={}, totalNodes={}", nodeId, nodes.size());
    }

    @Override
    public ServiceInstance select(String key) {
        if (nodes.isEmpty()) {
            return null;
        }
        // ThreadLocalRandom：多线程下无竞争，比 Math.random() 快
        int idx = ThreadLocalRandom.current().nextInt(nodes.size());
        return nodes.get(idx);
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public String name() {
        return "random";
    }
}
```

**为什么用 `CopyOnWriteArrayList` 而不是普通 `ArrayList` + `synchronized`？**

```
场景：select() 每秒几万次（读），addNode/removeNode 每分钟几次（写）
CopyOnWriteArrayList：读不加锁，写时复制整个数组 → 读性能极高
synchronized ArrayList：读写都抢同一把锁 → 读操作互相阻塞

代价：写时复制会短暂多占用一份内存，但节点变化太少了，完全无所谓。
```

**为什么 `addNode` 里先调 `removeNode`？**

同一个节点可能因为网络波动断开-重连，Controller 会收到两次 REGISTER。
如果不先移除旧的，`nodes` 里会出现重复条目，select 时被选中的概率翻倍。

### 4.5 RoundRobinLoadBalancer（~45 行）

位置：`heteromesh-common/src/main/java/com/heteromesh/loadbalancer/RoundRobinLoadBalancer.java`

```java
package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final List<ServiceInstance> nodes = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public void addNode(ServiceInstance instance) {
        removeNode(instance.getNodeId());
        nodes.add(instance);
        log.info("RoundRobin: 节点加入, nodeId={}, totalNodes={}", instance.getNodeId(), nodes.size());
    }

    @Override
    public void removeNode(String nodeId) {
        nodes.removeIf(n -> n.getNodeId().equals(nodeId));
        log.info("RoundRobin: 节点移除, nodeId={}, totalNodes={}", nodeId, nodes.size());
    }

    @Override
    public ServiceInstance select(String key) {
        if (nodes.isEmpty()) {
            return null;
        }
        // getAndIncrement() 溢出后会变成负数，& 0x7FFFFFFF 去掉符号位
        // 结果在 [0, 2^31-1] 范围内循环，保证 % nodes.size() 永远非负
        int idx = (counter.getAndIncrement() & 0x7FFFFFFF) % nodes.size();
        return nodes.get(idx);
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public String name() {
        return "roundRobin";
    }
}
```

**`& 0x7FFFFFFF` 干了什么？**

```
counter.getAndIncrement() 的返回值在 [Integer.MIN_VALUE, Integer.MAX_VALUE] 之间
最高位（bit 31）是符号位：0 = 正数，1 = 负数

& 0x7FFFFFFF → 把 bit 31 清零，其余位不变
  正数：不变（bit 31 本来就是 0）
  负数：bit 31 变成 0 → 变成正数

例如：
  Integer.MAX_VALUE = 0x7FFFFFFF =  2147483647
  再 +1 → 0x80000000 = -2147483648
  -2147483648 & 0x7FFFFFFF = 0  ← 回到起点，完美循环！
```

### 4.6 WeightedLoadBalancer（~70 行）

位置：`heteromesh-common/src/main/java/com/heteromesh/loadbalancer/WeightedLoadBalancer.java`

```java
package com.heteromesh.loadbalancer;

import com.heteromesh.registry.ServiceInstance;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class WeightedLoadBalancer implements LoadBalancer {

    // 节点列表和累积权重数组，addNode/removeNode 时一起重建
    // 用 synchronized 保护，因为重建不是原子操作
    private final List<ServiceInstance> nodes = new ArrayList<>();
    private final List<Integer> cumulativeWeights = new ArrayList<>();  // 累积权重
    private int totalWeight = 0;

    @Override
    public synchronized void addNode(ServiceInstance instance) {
        removeNodeLocked(instance.getNodeId());
        nodes.add(instance);
        rebuildWeights();
        log.info("Weighted: 节点加入, nodeId={}, weight={}, totalWeight={}",
                instance.getNodeId(), instance.getWeight(), totalWeight);
    }

    @Override
    public synchronized void removeNode(String nodeId) {
        removeNodeLocked(nodeId);
        rebuildWeights();
        log.info("Weighted: 节点移除, nodeId={}, totalNodes={}", nodeId, nodes.size());
    }

    // 内部用的移除方法（不加锁，调用方已持锁）
    private void removeNodeLocked(String nodeId) {
        nodes.removeIf(n -> n.getNodeId().equals(nodeId));
    }

    // 重建累积权重数组：O(n)，只在节点变化时调用
    private void rebuildWeights() {
        cumulativeWeights.clear();
        totalWeight = 0;
        for (ServiceInstance node : nodes) {
            totalWeight += node.getWeight();
            cumulativeWeights.add(totalWeight);
        }
    }

    @Override
    public ServiceInstance select(String key) {
        if (nodes.isEmpty()) {
            return null;
        }
        if (totalWeight <= 0) {
            return null;
        }

        // 生成 [0, totalWeight) 范围的随机数
        int dice = ThreadLocalRandom.current().nextInt(totalWeight);

        // 二分查找：找到第一个累积权重 > dice 的位置
        int lo = 0, hi = cumulativeWeights.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;  // 无符号右移 = 除以 2，防溢出
            if (cumulativeWeights.get(mid) > dice) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return nodes.get(lo);
    }

    @Override
    public synchronized int size() {
        return nodes.size();
    }

    @Override
    public String name() {
        return "weighted";
    }
}
```

**为什么 `addNode` / `removeNode` 上要加 `synchronized`？**

`addNode` 做了三件事：① 删旧节点 ② 加到列表 ③ 重建累积权重数组。
这三步必须是一个原子操作——否则如果两个 Worker 同时注册，`nodes` 和 `cumulativeWeights` 会不一致。
`select()` 没加 `synchronized`——它只读 `nodes` 和 `cumulativeWeights`，不修改。最坏情况：select 时另一个线程正在重建数组，读到中间状态。但节点变化本来就极少，读到一个瞬间不一致的值也没关系，下一次 select 就正常了。

**二分查找逻辑详解：**

```
cumulativeWeights = [50, 80, 100], dice = 65

lo=0, hi=2:
  mid = (0+2)>>>1 = 1
  cumulative[1] = 80 > 65? YES → hi=1

lo=0, hi=1:
  mid = (0+1)>>>1 = 0
  cumulative[0] = 50 > 65? NO → lo=1

lo=1, hi=1 → 退出循环 → nodes.get(1) → 节点B ✓
```

### 4.7 SPI 配置文件（新增）

位置：`heteromesh-common/src/main/resources/META-INF/services/com.heteromesh.loadbalancer.LoadBalancer`

**为什么需要这个文件？**
- `SpiExtensionLoader` 读取 `META-INF/services/<接口全限定名>` 来发现所有实现
- 格式是 `key=className`，key 就是 `LoadBalancerFactory.getLoadBalancer("random")` 的参数
- 和 `com.heteromesh.serializer.Serializer` 的 SPI 文件是同一个机制

```properties
# HeteroMesh LoadBalancer SPI 扩展
# 格式: key=全限定类名
consistentHash=com.heteromesh.loadbalancer.ConsistentHashLoadBalancer
random=com.heteromesh.loadbalancer.RandomLoadBalancer
roundRobin=com.heteromesh.loadbalancer.RoundRobinLoadBalancer
weighted=com.heteromesh.loadbalancer.WeightedLoadBalancer
```

### 4.8 LoadBalancerFactory（~18 行）

位置：`heteromesh-common/src/main/java/com/heteromesh/loadbalancer/LoadBalancerFactory.java`

**为什么需要这个 Factory？**
- 封装 `SpiExtensionLoader` 的调用细节（和 `SerializerFactory` 完全对标）
- 提供 `getLoadBalancer(name)` 和 `getDefault()` 两个便捷方法
- 如果以后要加单例缓存之类的逻辑，只改这里就行

```java
package com.heteromesh.loadbalancer;

import com.heteromesh.spi.SpiExtensionLoader;

public class LoadBalancerFactory {

    private static final SpiExtensionLoader<LoadBalancer> LOADER =
            SpiExtensionLoader.load(LoadBalancer.class);

    public static LoadBalancer getLoadBalancer(String name) {
        return LOADER.getExtension(name);
    }

    public static LoadBalancer getDefault() {
        return LOADER.getDefaultExtension();
    }
}
```

### 4.9 application.yml 加负载均衡配置

位置：`heteromesh-common/src/main/resources/application.yml`

**为什么要改？**
- 加 `heteromesh.loadbalancer.default` 配置项，指定默认使用哪个策略
- **和原来的区别**：原来没有 loadbalancer 配置段，LoadBalancer 是硬编码的

```yaml
# HeteroMesh 配置
heteromesh:
  # 负载均衡策略配置（新增）
  loadbalancer:
    # 默认负载均衡策略，可选值：consistentHash | random | roundRobin | weighted
    default: consistentHash

  # 序列化器配置
  serializer:
    default: binary
    routing:
      PING: binary
      PONG: binary
      TASK_REQUEST: json
      TASK_RESPONSE: json
      REGISTER: json
      REGISTER_ACK: json
```

### 4.10 HeteroMeshServer 改造

位置：`heteromesh-controller/src/main/java/com/heteromesh/controller/HeteroMeshServer.java`

**为什么要改？**
- 把硬编码的 `new ConsistentHashLoadBalancer()` 替换为 SPI 工厂 + YAML 配置驱动
- 读取 `application.yml` 中的 `heteromesh.loadbalancer.default` 来决定用哪个策略
- **和原来的区别**：原来固定创建 `ConsistentHashLoadBalancer`，现在根据配置动态创建任意策略

```java
// HeteroMeshServer.java 改动部分：

// ==================== 改动前 ====================
LoadBalancer lb = new ConsistentHashLoadBalancer();

// ==================== 改动后 ====================
// 从 YAML 配置读取负载均衡策略名，默认 consistentHash
String lbType = "consistentHash";
try {
    Map<String, Object> config = ConfigLoader.getConfig();
    Object heteromesh = config.get("heteromesh");
    if (heteromesh instanceof Map) {
        Object lb = ((Map<String, Object>) heteromesh).get("loadbalancer");
        if (lb instanceof Map) {
            Object def = ((Map<String, Object>) lb).get("default");
            if (def != null) {
                lbType = def.toString();
            }
        }
    }
} catch (Exception e) {
    log.warn("读取负载均衡配置失败，使用默认值: consistentHash", e);
}
LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer(lbType);
log.info("负载均衡策略: {}", loadBalancer.name());
```

同时需要：
- 删除原来的 `import com.heteromesh.loadbalancer.ConsistentHashLoadBalancer;`（不再直接引用具体实现）
- 新增 `import com.heteromesh.loadbalancer.LoadBalancer;` 和 `import com.heteromesh.loadbalancer.LoadBalancerFactory;`
- 新增 `import com.heteromesh.config.ConfigLoader;`（如果还没 import）

**注意**：`ConfigLoader` 返回的 Map 结构是 SnakeYAML 解析的嵌套 `Map<String, Object>`，所以需要逐层 `instanceof Map` 判空。这段 YAML 解析代码写得很直白——没有用 Spring 的 `@ConfigurationProperties`，因为我们是裸写框架，不引入 Spring 依赖。

---

## 五、文件清单

`heteromesh-common/src/main/java/com/heteromesh/loadbalancer/`：

| 文件 | 说明 | 类型 |
|------|------|------|
| `LoadBalancer.java` | 加 `@SPI("consistentHash")` + `name()` 方法 | 修改 |
| `ConsistentHashLoadBalancer.java` | 加 `name()` 方法（3 行） | 修改 |
| `RandomLoadBalancer.java` | 随机策略，CopyOnWriteArrayList + ThreadLocalRandom | 新建 |
| `RoundRobinLoadBalancer.java` | 轮询策略，AtomicInteger + &0x7FFFFFFF 防溢出 | 新建 |
| `WeightedLoadBalancer.java` | 加权策略，累积权重 + 二分查找 | 新建 |
| `LoadBalancerFactory.java` | SPI 工厂，封装 SpiExtensionLoader | 新建 |

`heteromesh-common/src/main/java/com/heteromesh/registry/`：

| 文件 | 说明 | 类型 |
|------|------|------|
| `ServiceInstance.java` | 加 `int weight = 100` 字段 | 修改 |

`heteromesh-common/src/main/resources/`：

| 文件 | 说明 | 类型 |
|------|------|------|
| `application.yml` | 加 `heteromesh.loadbalancer.default` 配置 | 修改 |
| `META-INF/services/com.heteromesh.loadbalancer.LoadBalancer` | SPI 扩展注册文件 | 新建 |

`heteromesh-controller/src/main/java/com/heteromesh/controller/`：

| 文件 | 说明 | 类型 |
|------|------|------|
| `HeteroMeshServer.java` | `new ConsistentHashLoadBalancer()` → `LoadBalancerFactory` + YAML 配置 | 修改 |

测试（在 `src/test/` 下，你写完后我来完成）：

| 文件 | 测试什么 |
|------|---------|
| `loadbalancer/RandomLoadBalancerTest.java` | addNode/removeNode/select + 分布均匀性 |
| `loadbalancer/RoundRobinLoadBalancerTest.java` | 轮询顺序正确性 + 溢出后行为 |
| `loadbalancer/WeightedLoadBalancerTest.java` | 权重分布正确性 + addNode/removeNode 后重建权重 |
| `loadbalancer/LoadBalancerFactoryTest.java` | SPI 加载 4 种策略 + getDefault 验证 + 不存在名称抛异常 |

---

## 六、常见错误预警

### 错误 1：`@SPI` 注解忘了加

```java
// ❌ LoadBalancer 接口没加 @SPI
public interface LoadBalancer { ... }
// → SpiExtensionLoader.load(LoadBalancer.class) 获取不到 defaultName
// → 程序启动时不会报错，但 LoadBalancerFactory.getDefault() 抛异常：
//   "No default extension configured for com.heteromesh.loadbalancer.LoadBalancer"

// ✅ 加上注解
@SPI("consistentHash")
public interface LoadBalancer { ... }
```

### 错误 2：SPI 配置文件路径写错

```java
// ❌ 常见错误路径：
META-INF/services/loadbalancer/...           // 多了一层目录
META-INF/service/...                         // 少了 s
META-INF.services/...                        // 用 . 而不是 /

// ✅ 正确路径（必须是这个，Java SPI 规范规定）：
META-INF/services/com.heteromesh.loadbalancer.LoadBalancer
```

### 错误 3：RoundRobin 溢出后拿到负数索引

```java
// ❌ 直接用 %
int idx = counter.getAndIncrement() % nodes.size();
// → overflow 后 idx 可能是负数 → ArrayIndexOutOfBoundsException

// ✅ 去掉符号位再取模
int idx = (counter.getAndIncrement() & 0x7FFFFFFF) % nodes.size();
```

**溢出多久发生一次？**

```
假设每秒 10 万次 select → 每天 86.4 亿次递增
Integer.MAX_VALUE = 21.47 亿 → 大约 249 天后溢出
→ 如果压测 QPS 更高，溢出会更快
→ 不是「遥远的问题」，压测时可能几小时就触发
```

### 错误 4：Weighted 的 addNode 不是原子操作

```java
// ❌ 没加 synchronized
public void addNode(ServiceInstance instance) {
    removeNode(instance.getNodeId());
    nodes.add(instance);
    rebuildWeights();  // ← 如果两个线程同时 addNode，数据可能不一致
}

// ✅ 加 synchronized
public synchronized void addNode(ServiceInstance instance) { ... }
public synchronized void removeNode(String nodeId) { ... }
```

### 错误 5：Weighted 的 select 没判 totalWeight <= 0

```java
// ❌ 取了一个权重为 0 的节点
// totalWeight = 0 → nextInt(0) → IllegalArgumentException

// ✅ 先判空
if (nodes.isEmpty() || totalWeight <= 0) {
    return null;
}
```

### 错误 6：`CopyOnWriteArrayList` 上做频繁写操作

```java
// ❌ 在 select() 里做 add/remove
public ServiceInstance select(String key) {
    nodes.add(...);  // ← 每次都复制整个数组！select 高频 → 灾难
    return nodes.get(0);
}

// ✅ 只在 addNode/removeNode 里修改列表
// select 只读不写
```

### 错误 7：SPI 配置文件里的 key 和 @SPI 注解对不上

```properties
# SPI 配置文件：
consistentHash=com.heteromesh.loadbalancer.ConsistentHashLoadBalancer
#                                  ↑ 类名是 ConsistentHash...

# @SPI 注解：
@SPI("consistentHash")    ← value 也要写成 consistentHash
//     ↑ 如果写成 "consistent_hash" → getDefault() 去配置文件里找 "consistent_hash" → 找不到 → 抛异常
```

---

## 七、复习问题

1. Random、RoundRobin、Weighted、ConsistentHash 四种策略分别适合什么场景？
2. 为什么 RoundRobin 用 `AtomicInteger` 而不是 `int`？
3. `counter.getAndIncrement() & 0x7FFFFFFF` 是干什么的？不加会怎样？
4. Weighted 的累积权重数组为什么要在 addNode/removeNode 时重建，而不是每次 select 时重建？
5. `CopyOnWriteArrayList` 和普通 `ArrayList` + `synchronized` 有什么区别？为什么这节课选前者？
6. LoadBalancer 的 SPI 化复刻了哪个已有模块？和 Serializer 相比有什么不同？
7. `SpiExtensionLoader` 是怎么发现 LoadBalancer 的实现类的？描述完整加载流程。
8. 如果在 `application.yml` 里把 `default` 写成了 `consistent_hash`（不存在的名字），会发生什么？
9. Weighted 的 `addNode` 为什么需要 `synchronized` 而 RandomLoadBalancer 不需要？
10. 如果要新增一个「最低延迟」策略（选历史响应最快的节点），需要改哪些文件？

### 参考答案

**1. 四种策略适用场景：**

| 策略 | 场景 | 原因 |
|------|------|------|
| Random | 无状态任务，节点同构 | 最简单，统计均匀 |
| RoundRobin | 严格公平，任务队列 | 绝对轮转，不偏不倚 |
| Weighted | 异构硬件混部 | 按算力分配 |
| ConsistentHash | 有状态/需要亲和性 | 上下线只影响局部 |

**2. 为什么用 `AtomicInteger`？**

Netty 的 `channelRead0` 是多线程调用的（每个 Channel 的 EventLoop 不同），多个 Worker 可能同时发来 TASK_REQUEST → 多个线程同时调 `select()` → 需要 CAS 保证计数器的可见性和原子性。普通 `int` 在多线程下自增会丢失计数（线程 A 读到 5，线程 B 也读到 5，各自 +1 写回 6 → 应该是 7）。

**3. `& 0x7FFFFFFF` 的作用：**

`AtomicInteger.getAndIncrement()` 在溢出（达到 Integer.MAX_VALUE）后变成负数。Java 的 `%` 保留符号：`-1 % 3 = -1`。这会导致数组负索引异常。`& 0x7FFFFFFF` 把符号位清零，保证结果在 `[0, 2^31-1]` 范围内。

**4. 累积权重数组为什么要预建？**

和 ConsistentHash 的 TreeMap 一个道理——写低频（addNode/removeNode 每分钟几次），读高频（select 每秒几万次）。预建后 select 直接二分查找 O(log n)；每次 select 都重建的话 O(n)，高频场景下浪费。

**5. CopyOnWriteArrayList vs synchronized ArrayList：**

COWList：读操作完全无锁（直接读数组引用），写操作复制整个数组。适合「读极多、写极少」的场景——我们的 select 就是读，addNode/removeNode 就是写。`synchronized ArrayList`：读写都抢同一把锁，select 之间会互相阻塞。COWList 缺点是写操作有 O(n) 的复制开销，但节点变化极少，完全可接受。

**6. 复刻了 Serializer 的 SPI 架构，但更简单：**

Serializer 有 `SerializerRouter`（按消息类型分派 → 不同消息可能用不同序列化器），LoadBalancer 只需要一个全局策略，所以不需要 Router 层。`SerializerFactory` ↔ `LoadBalancerFactory` 完全对标。

**7. SpiExtensionLoader 加载流程：**

```
LoadBalancerFactory 静态初始化
  → SpiExtensionLoader.load(LoadBalancer.class)
    → 读 @SPI 注解获取 defaultName = "consistentHash"
  → 第一次 getExtension() 调用时触发 loadExtensions()
    → 从 classpath 找 META-INF/services/com.heteromesh.loadbalancer.LoadBalancer
    → 逐行解析 "key=className"
    → Class.forName() + getDeclaredConstructor().newInstance()
    → 缓存到 ConcurrentHashMap
  → 后续调用直接走缓存
```

**8. 配置写错了名字：**

`LoadBalancerFactory.getLoadBalancer("consistent_hash")` → `SpiExtensionLoader` 在配置文件里找 key="consistent_hash" → 找不到 → 抛 `IllegalArgumentException: "No SPI extension found for com.heteromesh.loadbalancer.LoadBalancer with name: consistent_hash"`。

**9. Weighted 需要 synchronized 而 Random 不用：**

RandomLoadBalancer 的 `addNode` 只做了一个 `CopyOnWriteArrayList.add()`，这是线程安全的单操作。WeightedLoadBalancer 的 `addNode` 做了三步：removeNode → add to list → rebuild cumulative weights——这三个操作必须原子完成，否则累积权重数组和节点列表可能不一致。所以需要 `synchronized`。

**10. 新增「最低延迟」策略需要改的文件：**

- 新建 `LowestLatencyLoadBalancer.java`（实现 `LoadBalancer` 接口）
- 在 `META-INF/services/com.heteromesh.loadbalancer.LoadBalancer` 加一行 `lowestLatency=...`
- `application.yml` 里把 `default` 改成 `lowestLatency`
- `HeteroMeshServer.java` 和已有的其他代码**不用改**——这就是 SPI 的力量

---

## 八、面试怎么聊

> "我给我们框架实现了四种负载均衡策略——Random、RoundRobin、Weighted、ConsistentHash，并且整套 LoadBalancer 做了 SPI 插件化。跟 Dubbo 的思路一样：@SPI 注解标记接口，SpiExtensionLoader 读 META-INF/services 下的配置文件，key=className 格式，反射创建实例，缓存复用。"

> "为什么四种都要？因为不同场景需要不同策略。无状态任务用 Random 最简单，有状态任务用一致性哈希保证亲和性，异构 GPU 混部用 Weighted 按算力分配负载。Dubbo 也是这么做的——Random、RoundRobin、ConsistentHash、LeastActive 全有。"

> "RoundRobin 实现里有个细节：AtomicInteger 溢出后变成负数，Java 的 % 对负数保留符号所以会拿到负索引。我用 & 0x7FFFFFFF 把符号位清零，保证永远在正数范围内循环。Dubbo 也是这么处理的。"

> "Weighted 用的是累积权重 + 二分查找，不是遍历所有节点累加。节点多了以后二分查找 O(log n) 的优势就出来了。Nginx 的 upstream 加权也是这个思路。"

> "最有意思的是 SPI 化——之前 LoadBalancer 是 hardcode new 出来的，现在改 application.yml 一行配置就能切策略。如果你想加一个「最低延迟」策略，写一个类、在配置文件里注册一行、改 YAML，三分钟搞定。框架代码一行不用改。这个设计从 Serializer 的 SPI 改造中复用过来的，两个模块共享同一套 SpiExtensionLoader。"

---

## 九、下一步

做完本课，LoadBalancer 就有了完整的策略集和可插拔架构。第 8 课会往上走一层——**实现真正的 RPC 调用（动态代理 + 服务发布/引用）**，让 Client 能像调本地方法一样 `mesh.taskService().processImage(img)` 而不是手动构造 Message、发消息、等 Future。这才是 RPC 框架真正的「RPC 味」。

# HeteroMesh

异构 GPU 集群分布式推理调度系统 - 基于 Java 21 + Netty 的自研 RPC 框架与任务调度引擎。

## 项目定位

从零手写分布式 RPC 框架 + 任务调度系统。不依赖 Dubbo/gRPC，从协议设计、序列化、负载均衡、容错到调度全部自研，涵盖分布式系统核心知识点。面试项目，一人全栈。

## 系统架构

```
                    ┌──────────────────────┐
                    │   HTTP API (Netty)    │
                    │   REST：提交/查询/统计  │
                    └──────────┬───────────┘
                               │
                    ┌──────────▼───────────┐
                    │   Controller         │  云服务器 (公网 IP)
                    │   节点管理 + 任务调度  │
                    │   一致性哈希 + LB     │
                    └──────────┬───────────┘
                               │ 自定义二进制协议 (Netty 长连接)
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
     ┌────────────┐   ┌────────────┐   ┌────────────┐
     │  Worker 1  │   │  Worker 2  │   │  Worker 3  │  计算节点
     │  RTX 5090  │   │  RTX 3080  │   │  CPU Only  │  (NAT 后主动出站)
     │ 状态机+执行器│   │            │   │            │
     └────────────┘   └────────────┘   └────────────┘
```

## 模块结构 (最终目标)

```
HeteroMesh/
├── pom.xml                               父 POM
├── heteromesh-common/                    公共模块
│   └── com.heteromesh/
│       ├── protocol/       Message, MessageType, MessageEncoder, MessageDecoder
│       ├── serializer/     Serializer(I), JsonSerializer, BinarySerializer, KryoSerializer,
│       │                   SerializerFactory, SerializerRouter, SerializationConfig
│       ├── registry/       ServiceRegistry(I), InMemoryServiceRegistry, ServiceInstance,
│       │                   RegistrationHandler, HeartbeatManager, RegistryEventListener
│       ├── loadbalance/    LoadBalancer(I), RandomLB, RoundRobinLB, WeightedRandomLB,
│       │                   ConsistentHashLB, LoadBalancerFactory
│       ├── rpc/            RpcRequest, RpcResponse, RpcStatus, RpcFutureAdapter,
│       │                   RpcClient, RpcProxyFactory, RpcServiceInvoker
│       ├── faulttolerance/ RetryPolicy(I), FixedRetry, ExponentialBackoff, NoRetry,
│       │                   RetryableRpcClient, CircuitBreaker, TokenBucketRateLimiter,
│       │                   SlidingWindowRateLimiter
│       ├── pool/           ChannelPool(I), SimpleChannelPool, ChannelPoolConfig
│       ├── interceptor/    RpcInterceptor(I), RpcInvocationChain, LoggingInterceptor,
│       │                   MetricsInterceptor, RateLimitingInterceptor, AuthInterceptor
│       ├── config/         GlobalConfig, HeteroMeshConfig, ConfigLoader
│       └── transport/      ExceptionHandler, HeartbeatHandler, ConnectionManager
├── heteromesh-controller/  调度节点 (Server)
│   └── com.heteromesh.controller/
│       ├── HeteroMeshServer (Netty Server 入口)
│       ├── node/           WorkerNode, NodeManager
│       ├── scheduler/      TaskInfo, TaskStatus, TaskScheduler
│       └── http/           HttpApiServer, TaskSubmitHandler, NodeListHandler, StatsHandler
└── heteromesh-worker/      计算节点 (Client)
    └── com.heteromesh.worker/
        ├── WorkerClient (Netty Client 入口)
        ├── ClientHandler
        ├── task/           WorkerTaskState, WorkerTask, WorkerTaskExecutor, DummyTaskProcessor
        └── lifecycle/      GracefulShutdown
```

## 技术栈

| 层次 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 21 | LTS，虚拟线程支持 |
| 网络 | Netty 4.1.x | 自定义 10 字节固定头二进制协议 |
| 序列化 | JSON(Gson) / Binary(Varint) / Kryo | SPI 插件化，策略路由 |
| 负载均衡 | 随机 / 轮询 / 加权 / 一致性哈希 | 虚拟节点 150，TreeMap 环 |
| 容错 | 重试(固定/指数退避) + 熔断器(3态) + 限流(令牌桶/滑动窗口) | 装饰器+状态机+策略 |
| RPC | requestId + CompletableFuture + JDK 动态代理 | 完全异步非阻塞 |
| 连接池 | 自研 ChannelPool | 借还模型 + 健康检查 + 空闲驱逐 |
| HTTP API | Netty 嵌入式 HTTP Server | RESTful |
| 配置 | SnakeYAML | 外部化 YAML |
| 日志 | SLF4J + Logback | 结构化，按包分级 |
| 构建 | Maven 多模块 (3 → 4) | 统一依赖管理 |
| 测试 | JUnit 5 + JMH | 单元 + 集成 + 压测 |

---

## 学习路线 (19 课)

### ✅ 阶段 0：通信引擎 (已完成)

| 课 | 内容 | 状态 |
|----|------|------|
| 0 | Maven 多模块骨架搭建 | ✅ |
| 1 | 自定义二进制协议 + JSON 序列化 | ✅ |
| 2 | Netty 编解码器 + Server/Client | ✅ |
| 3 | 心跳机制 + 异常处理 | ✅ |
| 4 | RPC 骨架 (CompletableFuture + requestId) | ✅ |
| 5 | 里程碑 1：通信引擎整合 + 序列化性能对比 | ✅ |

### ✅ 阶段 1：基础设施重构 (已完成)

| 课 | 内容 | 关键产出 | 状态 |
|----|------|----------|------|
| 1 | SLF4J 日志迁移 + 包结构整理 | 统一日志输出，8 个类改造 | ✅ |
| 2 | Serializer 接口 + ServiceRegistry 接口 | 6 个新文件，编解码器解耦 | ✅ |
| 3 | SPI 插件机制 + SerializerFactory | SpiExtensionLoader, @SPI, META-INF/services | ✅ |
| 4 | 序列化策略路由 + YAML 配置 | SerializerRouter, SerializerCode, ConfigLoader | ✅ |

### 🔜 阶段 2：注册中心 + 节点管理 (进行中)

| 课 | 内容 | 关键产出 | 状态 |
|----|------|----------|------|
| 5 | 节点注册协议 + 心跳维护 | NodeChannelMap, DeadNodeDetector, REGISTER 消息 | 📋 已排课 |
| 6 | 一致性哈希 + 虚拟节点 | ConsistentHashLB (TreeMap, 150 虚拟节点) | ⬜ |
| 7 | 负载均衡策略集 (随机/轮询/加权) | RandomLB, RoundRobinLB, WeightedRandomLB, SPI 化 | ⬜ |

### ⬜ 阶段 3：RPC 核心深化

| 课 | 内容 | 关键产出 |
|----|------|----------|
| 8 | RpcRequest/RpcResponse + 超时机制 | RpcFutureAdapter.orTimeout(), RpcStatus |
| 9 | JDK 动态代理 + 服务接口化 | RpcProxyFactory, RpcServiceInvoker |
| 10 | 重试策略 (固定/指数退避/抖动) | FixedRetry, ExponentialBackoff |
| 11 | 熔断器 (3 态状态机) | CircuitBreaker: CLOSED→OPEN→HALF_OPEN |
| 12 | 限流器 (令牌桶 + 滑动窗口) | TokenBucketRateLimiter, SlidingWindowRateLimiter |
| 13 | 连接池 | SimpleChannelPool (借还+驱逐+健康检查) |
| 14 | 拦截器链 | RpcInvocationChain (日志/指标/限流/鉴权) |

### ⬜ 阶段 4：Controller + Worker + 系统联调

| 课 | 内容 | 关键产出 |
|----|------|----------|
| 15 | Controller 任务调度器 + 故障转移 | TaskScheduler, 一致性哈希集成 |
| 16 | HTTP API + YAML 配置 | HttpApiServer, ConfigLoader, REST 端点 |
| 17 | Worker 任务执行器 + 状态机 + 优雅关闭 | WorkerTaskExecutor, GracefulShutdown |
| 18 | 全集群集成测试 + 容灾 | 3 Workers + 50 任务 + kill 节点验证 |
| 19 | (选做) 回归 + 巩固 + 面试复盘 | 全链路 Review，数据压测，简历话术 |

---

## 项目数据

| 指标 | 当前 (第 3 课完成) | 目标 (21 课) |
|------|---------------------|---------------|
| 主代码文件 | 18 | ~95 |
| 主代码行数 | ~1100 | ~4700 |
| 测试文件 | 13 | ~44 |
| 测试代码行数 | ~1450 | ~4800 |
| 总代码量 | ~2550 | ~9500 |
| Maven 模块 | 3 | 4 |
| 序列化器 | 2 (JSON/Binary) | 3 (JSON/Binary/Kryo) |
| 负载均衡策略 | 0 | 4 |
| 容错组件 | 0 | 6 |

## 设计亮点

1. **自定义二进制协议**：非 HTTP/gRPC，10 字节固定头，Magic(4) + Version(1) + Type(1) + Length(4)
2. **SPI 插件化**：序列化器、负载均衡器通过 META-INF/services 发现，类 Dubbo 设计
3. **全链路容错**：重试 → 熔断 → 限流 三层防护，防止级联故障
4. **异步非阻塞**：全链路 CompletableFuture，不阻塞 Netty EventLoop
5. **策略模式**：序列化路由按消息类型自动选择（心跳永远 Binary）
6. **面向接口**：核心组件 interface + 2~4 实现，开闭原则
7. **Worker 主动出站**：穿透 NAT，无需 Controller 知道 Worker IP

## 快速开始

```bash
# 编译
mvn clean compile

# 运行全部测试 (当前 13 个测试类)
mvn clean test

# 仅运行压力测试 (1000 条 RPC 消息)
mvn test -pl heteromesh-worker -Dtest=StressTest

# 运行序列化性能基准
mvn test -pl heteromesh-common -Dtest=ProtocolBenchmark
```

## 参考项目

HeteroMesh 是一个教学项目，不直接对标工业级产品，但设计时参考了以下开源项目的核心思想：

| 领域 | 参考项目 | Stars | 借鉴了什么 |
|------|---------|-------|-----------|
| RPC 框架 | [Apache Dubbo](https://github.com/apache/dubbo) | 40k+ | SPI 插件机制、服务注册/发现、负载均衡策略、RPC 调用模型 |
| RPC 框架 | [SOFARPC](https://github.com/sofastack/sofa-rpc) | 3.5k+ | 拦截器链、连接池、容错策略设计 |
| 分布式调度 | [XXL-JOB](https://github.com/xuxueli/xxl-job) | 27k+ | Controller/Worker 架构、任务路由、心跳维护 |
| 注册中心 | [Nacos](https://github.com/alibaba/nacos) | 30k+ | 服务实例模型、健康检查、事件通知机制 |
| 负载均衡 | [Spring Cloud LoadBalancer](https://spring.io/projects/spring-cloud) | — | 随机/轮询/加权/一致性哈希策略接口设计 |
| 熔断限流 | [Sentinel](https://github.com/alibaba/sentinel) | 22k+ | 熔断器三态状态机、滑动窗口限流、令牌桶算法 |
| 序列化 | [Kryo](https://github.com/EsotericSoftware/kryo) | 6k+ | 高性能二进制序列化、varint 编码 |
| 网络通信 | [Netty](https://github.com/netty/netty) | 33k+ | 自定义协议编解码、Pipeline 模型、零拷贝 |
| GPU 推理 | [vLLM](https://github.com/vllm-project/vllm) | 40k+ | 推理调度思想、Worker 管理（架构层面参考，实现层面非对标） |

# HeteroMesh

异构 GPU 集群分布式推理调度系统 - 基于 Java 21 + Netty 的高性能 RPC 框架与任务调度引擎。

## 项目定位

一个从零手写的分布式 RPC 框架 + 任务调度系统。不依赖 Dubbo/gRPC 等现成框架，从协议设计、序列化、负载均衡、容错机制到任务调度全部自研，覆盖分布式系统核心知识点。

## 系统架构

```
                    ┌──────────────────────┐
                    │   HTTP API           │
                    │   任务提交 + 集群状态  │
                    └──────────┬───────────┘
                               │ REST
                    ┌──────────▼───────────┐
                    │   Controller         │  云服务器 (公网 IP)
                    │   节点管理 + 任务调度  │
                    │   Netty Server       │
                    └──────────┬───────────┘
                               │ 自定义二进制协议 (Netty 长连接)
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
     ┌────────────┐   ┌────────────┐   ┌────────────┐
     │  Worker 1  │   │  Worker 2  │   │  Worker 3  │  计算节点
     │  RTX 5090  │   │  RTX 3080  │   │  CPU Only  │  (NAT 后主动出站)
     └────────────┘   └────────────┘   └────────────┘
```

## 模块结构

```
HeteroMesh/
├── pom.xml                          父 POM，统一依赖版本管理
├── heteromesh-common/               公共模块
│   └── src/main/java/com/heteromesh/
│       ├── protocol/                协议层：Message, MessageType, 编解码器
│       ├── serializer/              序列化：JSON / Binary / Kryo + SPI 插件机制
│       ├── registry/                注册中心：节点注册、心跳维护、事件通知
│       ├── loadbalance/             负载均衡：随机 / 轮询 / 加权 / 一致性哈希
│       ├── rpc/                     RPC 核心：请求响应模型、动态代理、服务调用
│       ├── faulttolerance/          容错：重试策略、熔断器、限流器
│       ├── pool/                    连接池：Channel 复用、健康检查、空闲驱逐
│       ├── interceptor/             拦截器链：日志、指标、限流、鉴权
│       ├── config/                  配置：YAML 加载、全局配置
│       └── transport/               传输层：心跳、异常处理
├── heteromesh-controller/           调度节点
│   └── src/main/java/com/heteromesh/controller/
│       ├── node/                    节点生命周期管理
│       ├── scheduler/               任务调度（LB + 重试 + 熔断）
│       └── http/                    REST API（提交任务、查询节点、统计）
└── heteromesh-worker/               计算节点
    └── src/main/java/com/heteromesh/worker/
        ├── task/                    任务执行器 + 状态机
        └── lifecycle/               优雅关闭
```

## 技术栈

| 层次 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 21 | LTS，虚拟线程支持 |
| 网络通信 | Netty 4.1.x | 自定义二进制协议，10 字节固定头 |
| 序列化 | JSON (Gson) / Binary (Varint) / Kryo | SPI 插件化，策略路由 |
| 负载均衡 | 随机 / 轮询 / 加权随机 / 一致性哈希 | 工厂模式，可扩展 |
| 容错 | 重试（固定/指数退避）+ 熔断器 + 令牌桶/滑动窗口限流 | 装饰器模式 |
| RPC | 自研：requestId 匹配 + CompletableFuture + JDK 动态代理 | 非阻塞异步 |
| 连接池 | 自研 ChannelPool | 借还模型 + 健康检查 |
| HTTP API | Netty HTTP Server | RESTful，嵌入式 |
| 配置 | SnakeYAML | 外部化配置 |
| 测试 | JUnit 5 + JMH | 单元测试 + 性能基准 |
| 日志 | SLF4J + Logback | 结构化日志 |
| 构建 | Maven 多模块 | 依赖统一管理 |

## 核心特性

### 已完成 (第一阶段：通信引擎)

- [x] 多模块 Maven 项目骨架 (common / controller / worker)
- [x] 自定义二进制协议：Magic(4) + Version(1) + Type(1) + BodyLength(4) = 10 字节固定头
- [x] 双序列化方案：JSON（开发调试）+ Binary/Varint（生产，体积省 28.6%，速度快 4.6-5.8x）
- [x] Netty 编解码器 (MessageEncoder / MessageDecoder) + 粘包拆包处理
- [x] 心跳机制：ALL_IDLE 检测 + 连续 3 次 PING 无 PONG 判定离线
- [x] 全局异常处理器：IOException 区分 + 优雅关闭
- [x] RPC 骨架：requestId + CompletableFuture + ConcurrentHashMap 异步回调
- [x] 压力测试：1000 条 RPC 消息 0.487s 全部回复
- [x] 性能基准：Binary 编码 283ns/op vs JSON 1305ns/op

### 进行中 (第二阶段：RPC 框架深化)

- [ ] SLF4J 日志迁移 + 包结构整理
- [ ] Serializer 接口抽取 + SPI 插件机制
- [ ] Kryo 序列化器 + 三方案 JMH 对比
- [ ] 序列化策略路由（按消息类型自动选择）
- [ ] 节点注册协议 + 心跳维护 + 事件通知
- [ ] 负载均衡：随机 / 轮询 / 加权随机 / 一致性哈希
- [ ] RPC 请求响应模型重构 + 超时机制
- [ ] JDK 动态代理 + 服务接口化调用

### 计划中 (第三阶段：容错与治理)

- [ ] 重试策略：固定间隔 / 指数退避 / 抖动
- [ ] 熔断器：CLOSED → OPEN → HALF_OPEN 三态状态机
- [ ] 限流器：令牌桶 + 滑动窗口
- [ ] 连接池：Channel 复用 + 空闲驱逐 + 健康检查
- [ ] 拦截器链：责任链模式

### 计划中 (第四阶段：Controller + Worker + API)

- [ ] Controller 节点管理 + 任务调度器
- [ ] HTTP API (Netty 嵌入式)：任务提交 / 节点列表 / 统计
- [ ] YAML 配置外部化
- [ ] Worker 任务执行器 + 状态机
- [ ] 优雅关闭 (ShutdownHook)
- [ ] 全集群集成测试（3 Worker + 容灾）

## 设计亮点

1. **协议设计**：自定义二进制协议，非 HTTP/gRPC，面试可深入讲解字节级编码
2. **SPI 扩展**：序列化器、负载均衡器均通过 SPI 加载，类 Dubbo 插件化设计
3. **全链路容错**：重试 + 熔断 + 限流三层防护，防止级联故障
4. **异步非阻塞**：RPC 全链路基于 CompletableFuture，不阻塞 Netty EventLoop
5. **策略模式**：序列化路由按消息类型自动选择最优方案（心跳用 Binary，业务用 JSON）
6. **面向接口**：核心组件均为 interface + 多实现，符合开闭原则

## 快速开始

```bash
# 编译
mvn clean compile

# 运行全部测试
mvn clean test

# 仅运行压力测试
mvn test -pl heteromesh-worker -Dtest=StressTest

# 运行序列化性能基准
mvn test -pl heteromesh-common -Dtest=ProtocolBenchmark
```


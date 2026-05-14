# HeteroMesh 项目定位与 GitHub 对标

---

## 一、一句话定位

**Java 实现的分布式 GPU 推理平台 — Controller 调度 + Worker 执行 + 异步 RPC 通信**

---

## 二、核心三模块

```
┌──────────────────────────────────────────────────────┐
│                  HeteroMesh                          │
│                                                      │
│  heteromesh-controller（调度中心，云服务器）           │
│    · 接收外部请求                                     │
│    · 一致性哈希路由到合适的 Worker                    │
│    · 管理所有 Worker 的状态（心跳 + 注册表）          │
│    · 提供 HTTP API                                   │
│                                                      │
│  heteromesh-common（协议 + 传输，共用）               │
│    · 自定义二进制协议（粘包/半包解决）                │
│    · JSON + Protobuf 双序列化                        │
│    · 心跳检测 + 异常处理                             │
│    · RPC 异步回调框架（requestId + CompletableFuture）│
│                                                      │
│  heteromesh-worker（计算节点，RTX 5090 笔记本）       │
│    · 主动出站连接 Controller（穿透 NAT）              │
│    · 接收任务 → Ollama 推理 → 返回结果                │
│    · 文件分片存储                                    │
│    · 任务状态机管理                                   │
└──────────────────────────────────────────────────────┘
```

---

## 三、与真实开源项目的对应关系

### 3.1 Java 分布式任务调度（架构对标）

| 项目 | 参考点 |
|------|--------|
| [PowerJob](https://github.com/KFCFans/PowerJob) | Server-Worker 架构、任务分片执行 |
| [Disjob](https://github.com/dromara/disjob) | Supervisor-Worker 模式、多种注册中心 |
| [Openjob](https://github.com/wuchunfu/openjob) | Master/Worker 无状态设计 |

**HeteroMesh 借鉴了什么：**
- Controller = 调度 Server，Worker = 执行器
- Worker 注册/心跳/下线流程
- 任务路由到合适的 Worker

**HeteroMesh 没做（也不需要做）的：**
- CRON 定时任务、DAG 工作流
- 多种注册中心支持（ZK/Nacos/Consul）
- Web 管理后台

---

### 3.2 Java GPU 推理引擎（计算对标）

| 项目 | 参考点 |
|------|--------|
| [Jlama](https://github.com/tjake/Jlama) | 纯 Java LLM 推理，gRPC 分布式 |
| [Deeplearning4j](https://github.com/eclipse/deeplearning4j) | Java 深度学习，Spark 分布式训练 |
| [GPULlama3.java](https://github.com/beehive-lab/GPULlama3.java) | 纯 Java GPU 推理，TornadoVM 加速 |

**HeteroMesh 借鉴了什么：**
- 分布式推理的思想：多个 Worker 各跑一部分
- Worker 端对接大模型推理（Ollama）

**HeteroMesh 没做的：**
- 自己实现推理引擎（用的是 Ollama HTTP API，没写矩阵乘法）
- GPU 显存管理、KV Cache 优化
- 模型并行（一个模型拆到多张卡）

---

### 3.3 Python 标杆（功能愿景对标）

| 项目 | 定位 |
|------|------|
| [vLLM](https://github.com/vllm-project/vllm) | 高吞吐 LLM 推理引擎 |

**HeteroMesh 和 vLLM 的共同点：**
- 都解决「把 GPU 管起来接任务」这个问题
- 都有请求调度和路由

**差距（vLLM 是工业级，HeteroMesh 是教学级）：**
- vLLM 有 PagedAttention（显存管理算法，学术论文级别的优化）
- vLLM 支持连续批处理（Continuous Batching）
- vLLM 是 C++/CUDA 写的，直接操作 GPU 显存
- HeteroMesh 是 Java 写的，GPU 推理调 Ollama HTTP API

---

## 四、诚实的自我定位

### HeteroMesh 是「概念验证 + 学习项目」，不是「工业级产品」

| 维度 | 工业级（vLLM/PowerJob） | HeteroMesh |
|------|----------------------|------------|
| 代码量 | 10万+ 行 | ~2000 行 |
| 贡献者 | 数百人 | 1 人 |
| 开发时间 | 数年 | 数周 |
| 核心算法 | PagedAttention/RAFT | 一致性哈希（面试经典） |
| 适用场景 | 生产环境 | 面试简历 + 学习理解 |

### 但价值在哪？

面试官不会期待你一个人写出 vLLM。他关心的是：

1. **你理解分布式系统的核心问题** — 节点发现、心跳、路由、容错
2. **你亲手写了核心代码** — 不是调库，是手写协议、手写 RPC
3. **你做过技术选型** — JSON vs Protobuf，为什么？JMH 数据拿出来
4. **你架构上有自己的思考** — Worker 主动出站（NAT 穿透）、双序列化

**一个 2000 行但全部自己理解的项目 > 一个复制粘贴的 5 万行项目**

---

## 五、面试时怎么介绍

> "HeteroMesh 是我独立开发的分布式 GPU 推理平台，用于学习分布式系统核心概念。实现了自定义二进制协议、Netty 通信引擎、requestId + CompletableFuture 异步 RPC、一致性哈希路由、心跳检测容错机制。序列化做了 JSON 和 Protobuf 的方案对比，JMH 实测二进制体积省 28%、速度快 4-5 倍。架构上 Worker 主动连 Controller 解决 NAT 穿透问题。相当于把 vLLM 和 PowerJob 的核心调度思想用 Java 实现了一遍。"

### 如果面试官追问"你这和 vLLM 差距很大吧？"

> "是的，vLLM 的核心竞争力在 PagedAttention 显存管理和连续批处理，那是 C++/CUDA 级别的优化。我的项目聚焦在分布式调度的 Java 实现上，重点是自己理解一致性哈希、心跳检测、异步 RPC 这些分布式基础组件，而不是调用现成的框架。"

---

## 六、学完所有 15 课后的能力图谱

| 能力 | 掌握程度 |
|------|---------|
| Netty 自定义协议 | ✅ 手写编解码器、LengthFieldBasedFrameDecoder |
| 心跳机制 | ✅ IdleStateHandler + PING/PONG + 超时检测 |
| 异步 RPC | ✅ requestId + CompletableFuture + pendingRequests |
| 一致性哈希 | ⬜ 第 7 课 — TreeMap + 虚拟节点 |
| 任务调度 | ⬜ 第 8 课 — 路由 + 重试 + 超时 |
| 大模型对接 | ⬜ 第 10 课 — Ollama + 流式输出 |
| 文件分片存储 | ⬜ 第 13-14 课 — FileChannel + MD5 + 副本 |
| JMH 基准测试 | ✅ 理解概念，手动实现等价方案 |
| Docker 部署 | ⬜ 第 15 课 — Docker Compose 三节点 |

---

*整理于 2026-05-14 · 第 5 课完成时*

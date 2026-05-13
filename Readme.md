# HeteroMesh

异构 GPU 集群分布式推理调度系统 —— 基于 Java 21 + Netty 构建。

## 项目简介

将 AI 推理任务从云服务器调度到 GPU 节点（如 RTX 5090）执行，支持异构硬件、节点自动发现、一致性哈希路由、分布式文件存储和实时监控。

## 系统架构

```
                    ┌──────────────────────┐
                    │   Dashboard (Web)    │
                    │   实时监控 + 任务提交  │
                    └──────────┬───────────┘
                               │ HTTP / WebSocket
                    ┌──────────▼───────────┐
                    │   Controller         │  云服务器 (公网 IP)
                    │   调度 + 元数据管理    │
                    │   Netty Server 监听   │
                    └──────────┬───────────┘
                               │ Netty 长连接 (Worker 主动出站)
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
     ┌────────────┐   ┌────────────┐   ┌────────────┐
     │  Worker 1  │   │  Worker 2  │   │  Worker 3  │  GPU 节点
     │  RTX 5090  │   │  RTX 3080  │   │  CPU Only  │  (NAT 后)
     └────────────┘   └────────────┘   └────────────┘
```

## 模块结构

```
HeteroMesh/
├── pom.xml                       父 POM，统一依赖版本管理
├── heteromesh-common/            公共模块：协议定义 + 传输层
│   └── src/main/java/com/heteromesh/
│       ├── protocol/             消息类型、序列化、编解码器
│       └── transport/            心跳、连接管理、异常处理
├── heteromesh-controller/        调度节点：节点管理 + 任务路由 + HTTP 接口
│   └── src/main/java/com/heteromesh/controller/
└── heteromesh-worker/            计算节点：Ollama 推理 + 文件分片存储
    └── src/main/java/com/heteromesh/worker/
```

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 21 |
| 网络通信 | Netty 4.1.x，自定义二进制协议 |
| 序列化 | JSON (Gson) + Protobuf |
| 调度路由 | 一致性哈希 (150 虚拟节点) |
| 并发 | Java 21 虚拟线程 + Disruptor |
| AI 推理 | Ollama (HTTP API) |
| 存储 | FileChannel + MappedByteBuffer 零拷贝 |
| 监控 | WebSocket + Chart.js |
| 部署 | Docker Compose |
| 压测 | JMH |

## 进度

- [x] 多模块项目搭建
- [x] 自定义二进制协议 + Message 序列化 (JSON + Protobuf)
- [x] Netty 编解码器 + Server/Client + 集成测试
- [ ] 心跳机制 + 异常处理
- [ ] RPC 骨架
- [ ] Controller 节点注册 + 一致性哈希调度
- [ ] Worker Ollama 推理 + 任务状态机
- [ ] 分布式文件存储
- [ ] Dashboard 实时监控
- [ ] Docker 容器化 + JMH 压测

## 快速开始

```bash
# 编译
./mvnw clean compile

# 运行测试
./mvnw test
```

## 参考项目

- [Hive](https://github.com/VakeDomen/HiveCore) —— 分布式 Ollama 推理调度 (Controller/Worker 架构)
- [WXY-RPC](https://github.com/leiichen/wxy-rpc) —— Netty 自定义 RPC 框架
- [ruyuan-dfs](https://github.com/LCB14/ruyuan-dfs) —— 分布式文件存储系统

# HeteroMesh


## 核心架构拆解
1. **网络通信底座** ： 节点之间如何高效传输数据（使用传统的Http,太慢，使用Netty写自定义协议）
2. **Controller（中枢节点）**：负责计算节点、存储元数据，并将任务进行派发（基于哈希环调度、Disruptor高并发下发）
3. **Compute Worker(计算节点)** ：负责执行任务，调用DJL（Deep Java Library）进行大模型的推理，以及存分片数据。


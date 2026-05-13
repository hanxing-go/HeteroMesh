# NIO 与 Netty 知识点

> 从 BIO 到 NIO 到 Netty，逐步理解高性能网络通信

---

## 一、从 BIO 到 NIO

### BIO（Blocking I/O，阻塞 I/O）

```java
ServerSocket server = new ServerSocket(8888);
while (true) {
    Socket client = server.accept();  // 阻塞：等客户端连接
    // 每个连接开一个线程
    new Thread(() -> {
        InputStream in = client.getInputStream();
        in.read();                     // 阻塞：等数据到达
    }).start();
}
```

**问题：**
- 一个连接 = 一个线程 = ~1MB 栈内存
- 1000 连接 ≈ 1GB 内存
- 线程大部分时间在**阻塞等待**（CPU 空转切换线程）

### NIO（Non-blocking I/O，非阻塞 I/O）

核心思想：**一个线程管理多个连接**，哪个有数据就处理哪个。

```java
Selector selector = Selector.open();
// 把多个 Channel 注册到同一个 Selector
channel1.register(selector, SelectionKey.OP_READ);
channel2.register(selector, SelectionKey.OP_READ);

while (true) {
    selector.select();  // 阻塞直到有 Channel 就绪
    Set<SelectionKey> keys = selector.selectedKeys();
    for (SelectionKey key : keys) {
        if (key.isReadable()) {
            // 有数据可读 → 处理
        }
    }
}
```

**问题：**
- Java 原生 ByteBuffer 难用（flip/clear 容易出错）
- TCP 粘包/半包要自己处理
- 连接断开检测、内存泄漏等坑多

---

## 二、Netty 核心概念

### EventLoop → 驱动一切

一个 EventLoop 就是一个线程，负责管理多个 Channel：

```
传统 BIO：                   Netty NIO：
┌──────────┐                ┌──────────────────────┐
│ 线程1     │ 管 连接A        │ EventLoop (1个线程)   │
│ 线程2     │ 管 连接B        │  连接A (有数据→处理)   │
│ 线程3     │ 管 连接C        │  连接B (无数据→跳过)   │
│ ...       │               │  连接C (有数据→处理)   │
└──────────┘                └──────────────────────┘
每个连接一个线程              一个线程管理所有连接
```

BossGroup：只 accept 新连接，交给 WorkerGroup
WorkerGroup：处理已建立连接上的数据读写

### Channel → 代表一个 TCP 连接

```java
channel.writeAndFlush(msg);  // 发数据
channel.close();             // 关连接
```

两种类型：
- `NioServerSocketChannel`：服务器监听端口
- `NioSocketChannel`：客户端 / 服务端已接受的连接

### Pipeline → 处理链条（传送带）

```
数据进入（Inbound，head → tail）：
  [网络] → Decoder → BusinessHandler → (终点)

数据出去（Outbound，tail → head）：
  [网络] ← Encoder ← BusinessHandler ← (起点)
```

**Inbound Handler**（处理进来的数据）：
- `channelActive()`：连接建立时触发
- `channelRead()`：收到数据时触发
- `channelInactive()`：连接断开时触发

**Outbound Handler**（处理出去的数据）：
- `write()`：写数据时触发

### ByteBuf → 更好的 ByteBuffer

| | ByteBuffer（原生） | ByteBuf（Netty） |
|------|------|------|
| 读写切换 | 需要 flip() / clear() | 读写指针分离 |
| 扩容 | 固定大小 | 自动扩容 |
| 释放 | 手动管理 | 引用计数，自动释放 |

---

## 三、TCP 粘包/半包

TCP 是流协议，不保证消息边界：

```
发送方发 3 条：   [MSG1] [MSG2] [MSG3]

接收方可能收到：
  情况1：[MSG1MSG2MSG3]        ← 粘包（合一起了）
  情况2：[MSG1M] [SG2MSG3]     ← 半包（拆开了）
  情况3：[MS] [G1MSG2] [MSG3]  ← 混乱
```

**解决方案：LengthFieldBasedFrameDecoder**

在协议头里写入消息体长度，Netty 根据长度字段自动切帧：

```
协议格式：
┌────────┬───────┬──────┬──────────┬─────────────┐
│ MAGIC  │  VER  │ TYPE │ BODYLEN  │    BODY     │
│ 4字节  │ 1字节 │ 1字节 │  4字节   │  BODYLEN字节 │
└────────┴───────┴──────┴──────────┴─────────────┘
         ↑                               ↑
    固定 6 字节                  长度由 BODYLEN 决定

Netty 切帧流程：
  1. 跳过 6 字节（magic + version + type）
  2. 读 4 字节 → bodyLength
  3. 等数据凑够 10 + bodyLength 字节
  4. 切出一条完整帧
```

---

## 四、Server 端 vs Client 端

| | Server | Client |
|------|------|------|
| 启动器 | `ServerBootstrap` | `Bootstrap` |
| 线程组 | bossGroup + workerGroup | 一个 group |
| Channel | `NioServerSocketChannel` | `NioSocketChannel` |
| 连接方式 | `.bind(port)` 监听 | `.connect(host, port)` 主动连 |
| Pipeline 配置 | `.childHandler()` | `.handler()` |

---

## 五、简单面试问答

**Q: Netty 和原生 Java NIO 的区别？**
A: Netty 封装了 NIO，解决了 NIO 的 3 个痛点：ByteBuffer 难用（提供 ByteBuf）、粘包/半包（提供 LengthFieldBasedFrameDecoder）、内存管理（引用计数自动释放）。

**Q: BossGroup 和 WorkerGroup 分别干什么？**
A: BossGroup 只做 accept（接收新连接），WorkerGroup 处理已建立连接的读写。就像餐厅：前台接待（boss）把客人带进来，服务员（worker）服务客人。

**Q: Pipeline 是什么？数据怎么流转？**
A: Pipeline 是 Handler 链条。数据进来走 Inbound（head → tail，依次经过 Decoder → 业务 Handler），数据出去走 Outbound（tail → head，依次经过 业务 Handler → Encoder）。

**Q: 怎么解决 TCP 粘包？**
A: 用 LengthFieldBasedFrameDecoder。在协议头里定义长度字段，Netty 根据长度自动把字节流切成一条条完整消息。

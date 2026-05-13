# 第 2 课 · Netty 编解码器与 Server/Client 详解

> 完成时间：2026-05-13  
> 产出：MessageDecoder.java、MessageEncoder.java、HeteroMeshServer.java、ServerHandler.java、WorkerClient.java、ClientHandler.java、ClientServerIntegrationTest.java（2 个集成测试）
>
> 修复的 Bug：initialBytesToStrip 参数错误、ClientHandler 发送逻辑位置错误、方法名拼写错误

---

## 一、这节课解决了什么问题

第 1 课我们能在一个 JVM 里把 Message 编解码。这节课让它真正走网络：两个进程（Server/Client）通过 Netty 建立 TCP 连接，收发自定义协议消息。

```
第 1 课的能力                          第 2 课的能力
┌──────────┐                          ┌──────────┐     网络      ┌──────────┐
│ Message  │                          │ Client   │ ───TCP连接──→ │ Server   │
│   ↓      │                          │   ↓      │               │   ↓      │
│ encode() │  同一个 JVM              │ encode() │               │ decode() │
│   ↓      │                          │   ↓      │               │   ↓      │
│ ByteBuffer│                         │ ByteBuf  │               │ Message  │
│   ↓      │                          │   ↓      │               │   ↓      │
│ decode() │                          │ 网络发送  │               │ 业务处理  │
│   ↓      │                          │           │               │   ↓      │
│ Message  │                          │           │               │ encode() │
└──────────┘                          │           │ ←─────────── │  回复     │
                                      └──────────┘               └──────────┘
```

---

## 二、核心概念：Pipeline 的数据流向

这是本课最容易搞混的地方。Pipeline 上可以放多个 Handler，数据进来时走 Inbound 方向，出去时走 Outbound 方向：

```
Server 端 Pipeline：
                          
  [网络] ──→ MessageDecoder ──→ ServerHandler ──→ (终点)
              (Inbound)         (Inbound)
              
  [网络] ←── MessageEncoder ←── ServerHandler ←── (起点)
              (Outbound)         (Outbound)
```

**关键点：**
- **Inbound** 方向是 head → tail，Decoder 先处理，然后业务 Handler
- **Outbound** 方向是 tail → head，业务 Handler 先处理，然后 Encoder
- `channelRead0` 是 **Inbound**，收到数据才触发
- `channelActive` 也在 **Inbound** 方向，但它是连接建立时触发（不是收到数据）
- Encoder 写在 Decoder 后面（addLast），不代表数据先经过 Encoder——Netty 自动区分 Inbound/Outbound

### ChannelHandlerContext 是什么？

把 Pipeline 想象成流水线，每个 Handler 是一个**工位**，`ChannelHandlerContext` 就是这个工位的**控制面板**：

```java
ctx.writeAndFlush(msg);   // 从当前工位向 Outbound 方向发送（tail → head）
ctx.channel();            // 获取所属的 Channel 连接
ctx.fireChannelRead(msg); // 传递给下一个 Inbound Handler
```

---

## 三、Server 端：HeteroMeshServer + ServerHandler

### HeteroMeshServer.java

```java
EventLoopGroup bossGroup = new NioEventLoopGroup(1);    // 1 个线程，只 accept
EventLoopGroup workerGroup = new NioEventLoopGroup(4);  // 4 个线程，处理 I/O

new ServerBootstrap()
    .group(bossGroup, workerGroup)
    .channel(NioServerSocketChannel.class)  // 服务器用 NioServerSocketChannel
    .childHandler(new ChannelInitializer<>() {
        @Override
        protected void initChannel(SocketChannel ch) {
            ch.pipeline().addLast(
                new MessageDecoder(),   // 1. 拆帧 + 解码
                new MessageEncoder(),   // 2. 编码（出站时用）
                new ServerHandler()     // 3. 业务处理
            );
        }
    })
    .bind(port);  // 绑定端口
```

**BossGroup vs WorkerGroup：**
- BossGroup：前台接待，只负责 accept 新连接，然后把连接交给 WorkerGroup
- WorkerGroup：服务员，负责已建立连接上的所有数据读写

### ServerHandler.java

```java
public class ServerHandler extends SimpleChannelInboundHandler<Message> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
        System.out.println("收到消息: " + msg.getBody());
        
        // 回复消息：必须带上 requestId，这样客户端才能匹配请求
        Message reply = Message.createTaskResponse(msg.getRequestId(), "成功收到消息");
        ctx.writeAndFlush(reply);
    }
}
```

**为什么用 `SimpleChannelInboundHandler<Message>`？** 泛型 `<Message>` 让 Netty 自动类型转换——收到的 Object 自动转为 Message，用完自动释放内存。

---

## 四、Client 端：WorkerClient + ClientHandler

### WorkerClient.java

```java
new Bootstrap()
    .group(workerGroup)                    // 客户端只需一个 EventLoopGroup
    .channel(NioSocketChannel.class)       // 客户端用 NioSocketChannel
    .option(ChannelOption.SO_KEEPALIVE, true)  // 开启 TCP KeepAlive
    .handler(new ChannelInitializer<>() {
        @Override
        protected void initChannel(SocketChannel ch) {
            ch.pipeline().addLast(
                new MessageDecoder(),
                new MessageEncoder(),
                new ClientHandler()
            );
        }
    })
    .connect(host, port);  // 连接服务器（不是 bind！）
```

**ServerBootstrap vs Bootstrap：**
| | ServerBootstrap | Bootstrap |
|------|:---:|:---:|
| 用途 | 服务器监听端口 | 客户端连接服务器 |
| 线程组 | bossGroup + workerGroup | 一个 group |
| Channel | NioServerSocketChannel | NioSocketChannel |
| 方法 | `.bind(port)` | `.connect(host, port)` |
| Handler | `.childHandler()` | `.handler()` |

### ClientHandler.java（修复后）

```java
public class ClientHandler extends SimpleChannelInboundHandler<Message> {

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // ✅ 连接建立后触发：主动发消息
        ctx.writeAndFlush(Message.createTaskRequest("你好，服务器！"));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
        // ✅ 收到消息时触发：打印回复
        System.out.println("收到回复: " + msg.getBody());
    }
}
```

**关键区别：**
| 方法 | 触发时机 | 应该做的事 |
|------|---------|----------|
| `channelActive` | 连接建立成功时（只触发一次） | 发送初始化消息、注册请求 |
| `channelRead0` | 每次收到数据时 | 处理收到的消息、打印、回复 |

**常见错误：** 把发送放在 `channelRead0` → 双方都在等对方先说话 → 死锁。

---

## 五、编解码器详解

### MessageEncoder：出站编码

```
你的 Message 对象
      │
      ▼
MessageSerializer.encode(msg)    ← 第 1 课你写的，Message → ByteBuffer
      │
      ▼
out.writeBytes(buffer)            ← 写入 Netty 的 ByteBuf
      │
      ▼
发送到网络
```

```java
public class MessageEncoder extends MessageToByteEncoder<Message> {
    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, ByteBuf out) {
        ByteBuffer buffer = MessageSerializer.encode(msg);
        out.writeBytes(buffer);
    }
}
```

**泛型 `<Message>` 不能省略：** 如果写成 `MessageToByteEncoder`（raw type），编译器无法帮你检查类型——如果 Pipeline 上有其他类型的数据经过，会在运行时抛 ClassCastException。

### MessageDecoder：入站解码 + 拆帧

```java
public class MessageDecoder extends LengthFieldBasedFrameDecoder {
    public MessageDecoder() {
        // maxFrameLength=1MB, lengthFieldOffset=6, lengthFieldLength=4,
        // lengthAdjustment=0, initialBytesToStrip=0
        super(1024 * 1024, 6, 4, 0, 0);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);  // 父类切帧
        if (frame == null) return null;                   // 不够一条完整消息

        byte[] bytes = new byte[frame.readableBytes()];
        frame.readBytes(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return MessageSerializer.decode(buffer);           // 第 1 课的解码
    }
}
```

**继承 `LengthFieldBasedFrameDecoder` 解决了 TCP 粘包/半包问题：**

```
收到的原始字节流: [MSG1的前半截][MSG1的后半截+MSG2的前半截]...
                                   │
            LengthFieldBasedFrameDecoder 内部逻辑：
              1. 跳过 6 字节 → 读 4 字节 → 得到 bodyLength
              2. 等数据够 10 + bodyLength 字节
              3. 切出一条完整帧 → 传给 MessageSerializer.decode()
```

---

## 六、关键 Bug 修复：initialBytesToStrip

### 原始代码（错误）

```java
super(1024 * 1024, 6, 4, 0, 10);  // initialBytesToStrip = 10
```

### 问题分析

`initialBytesToStrip = 10` 意思是：LengthFieldBasedFrameDecoder 在拆帧后，**丢弃前 10 字节**。但 `MessageSerializer.decode()` 又从头开始读 MAGIC → 读到的实际上是 body 的前 4 字节。

```
协议格式：
┌────────┬───────┬──────┬──────────┬─────────────┐
│ MAGIC  │  VER  │ TYPE │ BODYLEN  │    BODY     │
│ 4字节  │ 1字节 │ 1字节 │  4字节   │  BODYLEN字节 │
└────────┴───────┴──────┴──────────┴─────────────┘
│←──────── 10字节头 ──────→│

initialBytesToStrip=10 → 丢弃头 → 只剩 BODY
                                        ↓
            MessageSerializer.decode() 从 BODY 第 0 字节开始读
                                        ↓
            读到 BODY 前 4 字节当 MAGIC → 魔法数字校验失败！
```

### 修复后

```java
super(1024 * 1024, 6, 4, 0, 0);  // initialBytesToStrip = 0
```

**不丢弃任何字节**，完整帧（10 字节头 + body）传给 `MessageSerializer.decode()`。拆帧归 Netty，解协议归 MessageSerializer——职责分离清晰。

---

## 七、集成测试

测试完整的 Client → Server 通信链路：

```java
// 1. 启动测试服务器（随机端口，端口 0 = OS 自动分配）
Channel serverChannel = new ServerBootstrap()
    .group(bossGroup, workerGroup)
    .channel(NioServerSocketChannel.class)
    .childHandler(/* pipeline 与 HeteroMeshServer 一致 */)
    .bind(0).sync().channel();  // bind(0) 让系统自动分配可用端口

int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

// 2. 启动客户端，连接该端口
new Bootstrap()
    .group(clientGroup)
    .channel(NioSocketChannel.class)
    .handler(/* pipeline 与 WorkerClient 一致 */)
    .connect("localhost", port).sync();

// 3. 用 CountDownLatch 等待消息交换完成
assertTrue(serverReceived.await(5, TimeUnit.SECONDS));
assertTrue(clientReceived.await(5, TimeUnit.SECONDS));

// 4. 断言消息内容
assertEquals("你好，服务器！", receivedByServer.get().getBody());
assertEquals("你好，客户端！", receivedByClient.get().getBody());
```

**两个测试用例：**

| 测试 | 验证内容 | 结果 |
|------|---------|:---:|
| `shouldExchangeMessageBetweenClientAndServer` | 单条消息收发，内容一致性 | ✅ |
| `shouldHandleMultipleMessages` | 连续发 10 条，验证无粘包/半包 | ✅ |

---

## 八、学到的 NIO/Netty 知识点总结

### BIO vs NIO vs Netty

```
BIO（Java 原生阻塞 I/O）：
  一个连接 → 一个线程 → 阻塞在 read() → CPU 大量时间在等
  问题：1000 连接 = 1000 线程 ≈ 1GB 内存

NIO（Java NIO 多路复用）：
  一个 Selector 线程 → 管理所有连接 → 有数据才处理 → CPU 高效
  问题：ByteBuffer 难用（flip/clear）、粘包/半包要手写、断连检测麻烦

Netty（对 NIO 的封装）：
  保留 NIO 的高性能 + 解决了 ByteBuffer/粘包/断连的坑
  核心改进：ByteBuf（读写分离）、Pipeline（可插拔）、LengthFieldBasedFrameDecoder（自动拆帧）
```

### 本课写过的类和职责

| 类 | 类型 | 职责 |
|------|------|------|
| `MessageDecoder` | Inbound | Netty 拆帧 + 转换为 Message 对象 |
| `MessageEncoder` | Outbound | Message 对象 → ByteBuf → 网络 |
| `HeteroMeshServer` | 启动器 | 绑定端口，配置 Pipeline，等待连接 |
| `ServerHandler` | Inbound | 收到消息 → 业务处理 → 回复 |
| `WorkerClient` | 启动器 | 连接服务器，配置 Pipeline |
| `ClientHandler` | Inbound | 连上后发消息，收到回复后打印 |

### 常犯错误清单

| 错误 | 症状 | 根因 |
|------|------|------|
| `initialBytesToStrip=10` | 魔法数字校验失败 | Netty 拆帧丢弃了头，Serializer 又从头读 |
| 发送写在 `channelRead0` | 双方都卡住 | 连接建立后没有发消息的入口 |
| `MessageToByteEncoder` 无泛型 | 编译警告 | Netty 无法做类型检查 |
| `connct()` 拼写错误 | 编译失败 | 少了一个 e，应该是 `connect()` |
| 类名 `ClientHander` | 编译失败 | 少了一个 l，应该是 `ClientHandler` |

---

## 九、与第 3 课的关系

第 2 课实现了基本的连接和消息收发。第 3 课要加上：

- **心跳机制**（`IdleStateHandler` + PING/PONG）：长连接需要定期确认对方还活着
- **异常处理**（`ExceptionHandler`）：区分正常断连（IOException）vs 真正的异常
- **连接管理**（`ConnectionManager`）：管理所有 Worker 连接，提供 add/remove/getAll

```
当前 Pipeline（第 2 课）：          第 3 课后：
Decoder → Encoder → Handler     Decoder → Encoder → IdleStateHandler → HeartbeatHandler → ExceptionHandler → Handler
```

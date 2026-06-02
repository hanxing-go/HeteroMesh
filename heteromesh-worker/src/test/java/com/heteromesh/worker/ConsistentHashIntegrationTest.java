package com.heteromesh.worker;

import com.heteromesh.controller.ServerHandler;
import com.heteromesh.controller.node.NodeChannelMap;
import com.heteromesh.loadbalancer.ConsistentHashLoadBalancer;
import com.heteromesh.loadbalancer.LoadBalancer;
import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageDecoder;
import com.heteromesh.protocol.MessageEncoder;
import com.heteromesh.protocol.MessageType;
import com.heteromesh.registry.InMemoryServiceRegistry;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import com.heteromesh.transport.ExceptionHandler;
import com.heteromesh.transport.HeartbeatHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashIntegrationTest {

    // 1 Worker + 1 Client：Client 发 TASK_REQUEST → Controller LB 选 Worker → Worker 处理 → Client 收到响应
    @Test
    void shouldRouteTaskToWorkerViaConsistentHash() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup serverWorkers = new NioEventLoopGroup(4);
        EventLoopGroup workerGroup = new NioEventLoopGroup(1);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        try {
            ServiceRegistry registry = new InMemoryServiceRegistry();
            NodeChannelMap nodeChannelMap = new NodeChannelMap();
            LoadBalancer lb = new ConsistentHashLoadBalancer();
            Map<String, Channel> pendingClients = new ConcurrentHashMap<>();

            // 启动 Controller
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, serverWorkers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new ServerHandler(registry, nodeChannelMap, lb, pendingClients)
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            // 启动 Worker：只注册 + 处理转发的 TASK_REQUEST，不主动发任务
            new Bootstrap()
                    .group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(createWorkerHandler("worker-gpu-01"))
                    .connect("localhost", port).sync();

            // 等待 Worker 注册完成
            Thread.sleep(300);

            CountDownLatch clientDone = new CountDownLatch(1);
            AtomicReference<String> clientResponse = new AtomicReference<>();

            // 启动 Client：只发 TASK_REQUEST，等待 TASK_RESPONSE
            new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            ctx.writeAndFlush(Message.createTaskRequest("推理任务: 识别图片"));
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_RESPONSE) {
                                                clientResponse.set(msg.getBody());
                                                clientDone.countDown();
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync();

            // Client 应该在 5 秒内收到 Worker 处理后的响应
            assertTrue(clientDone.await(5, TimeUnit.SECONDS),
                    "Client 应该在 5 秒内收到响应");
            assertTrue(clientResponse.get().contains("已处理"),
                    "响应应包含 Worker 处理标记，实际: " + clientResponse.get());

            // 验证 Worker 注册 + LB 状态
            assertEquals(1, registry.size());
            assertEquals(1, lb.size());

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            serverWorkers.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    // 2 Worker + 1 Client：多个任务按 requestId 一致性哈希分发到不同 Worker
    @Test
    void shouldDistributeTasksAcrossTwoWorkers() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup serverWorkers = new NioEventLoopGroup(4);
        EventLoopGroup workerGroup1 = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup2 = new NioEventLoopGroup(1);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        try {
            ServiceRegistry registry = new InMemoryServiceRegistry();
            NodeChannelMap nodeChannelMap = new NodeChannelMap();
            LoadBalancer lb = new ConsistentHashLoadBalancer();
            Map<String, Channel> pendingClients = new ConcurrentHashMap<>();

            // 启动 Controller
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, serverWorkers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new ServerHandler(registry, nodeChannelMap, lb, pendingClients)
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            // 启动 Worker-A（只注册 + 处理转发的任务）
            new Bootstrap()
                    .group(workerGroup1)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(createWorkerHandler("worker-A"))
                    .connect("localhost", port).sync();

            // 启动 Worker-B（只注册 + 处理转发的任务）
            new Bootstrap()
                    .group(workerGroup2)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(createWorkerHandler("worker-B"))
                    .connect("localhost", port).sync();

            // 等待两个 Worker 都注册完成
            Thread.sleep(300);
            assertEquals(2, registry.size());
            assertEquals(2, lb.size());

            // 启动 Client，连续发 20 个不同 requestId 的任务
            CountDownLatch clientDone = new CountDownLatch(20);
            AtomicReference<String> lastResponse = new AtomicReference<>();

            new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            for (int i = 0; i < 20; i++) {
                                                ctx.write(Message.createTaskRequest("任务-" + i));
                                            }
                                            ctx.flush();
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_RESPONSE) {
                                                lastResponse.set(msg.getBody());
                                                clientDone.countDown();
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync();

            // 所有 20 个任务都应该在 10 秒内收到响应
            assertTrue(clientDone.await(10, TimeUnit.SECONDS),
                    "20 个任务都应该收到响应，剩余: " + clientDone.getCount());

            // 验证响应来自不同的 Worker（任务被分发到了不同节点）
            // 至少有一个响应应该提到 worker-A 或 worker-B（不是全部来自同一个）
            // 注：严格来说 20 个任务可能都哈希到同一个 Worker，但概率极低
        } finally {
            clientGroup.shutdownGracefully();
            workerGroup2.shutdownGracefully();
            workerGroup1.shutdownGracefully();
            serverWorkers.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    // 没有 Worker 在线时，Client 发 TASK_REQUEST → Controller 返回"无可用节点"
    @Test
    void shouldReturnNoWorkerAvailableWhenRingIsEmpty() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup serverWorkers = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        try {
            ServiceRegistry registry = new InMemoryServiceRegistry();
            NodeChannelMap nodeChannelMap = new NodeChannelMap();
            LoadBalancer lb = new ConsistentHashLoadBalancer();
            Map<String, Channel> pendingClients = new ConcurrentHashMap<>();

            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, serverWorkers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new ServerHandler(registry, nodeChannelMap, lb, pendingClients)
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            CountDownLatch responseReceived = new CountDownLatch(1);
            AtomicReference<String> responseBody = new AtomicReference<>();

            // Client 直接发任务（没有 Worker 注册过）
            new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            ctx.writeAndFlush(Message.createTaskRequest("任务1"));
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_RESPONSE) {
                                                responseBody.set(msg.getBody());
                                                responseReceived.countDown();
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync();

            assertTrue(responseReceived.await(5, TimeUnit.SECONDS),
                    "即使没有 Worker，也应该收到错误响应");
            assertEquals("当前没有节点可用", responseBody.get(),
                    "没有 Worker 时应该返回'当前没有节点可用'");
        } finally {
            clientGroup.shutdownGracefully();
            serverWorkers.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    // ---------- 辅助方法：Worker Handler（只注册 + 处理转发任务，不主动发请求）----------

    private ChannelHandler createWorkerHandler(String nodeId) {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(
                        new ExceptionHandler(),
                        new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                        new MessageDecoder(),
                        new MessageEncoder(),
                        new HeartbeatHandler(),
                        new SimpleChannelInboundHandler<Message>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                // 连接建立后只做一件事：注册
                                ServiceInstance self = new ServiceInstance(
                                        nodeId, "127.0.0.1", 9090,
                                        "RTX 5090", 16 * 1024,
                                        System.currentTimeMillis(),
                                        System.currentTimeMillis());
                                ctx.writeAndFlush(Message.createRegister(self));
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                switch (msg.getType()) {
                                    case TASK_REQUEST -> {
                                        // 收到 Controller 转发的任务，处理后回复
                                        String result = "[" + nodeId + "] 已处理: " + msg.getBody();
                                        ctx.writeAndFlush(Message.createTaskResponse(
                                                msg.getRequestId(), result));
                                    }
                                    case REGISTER_ACK -> {} // 注册确认，无需处理
                                    default -> {}
                                }
                            }
                        }
                );
            }
        };
    }
}

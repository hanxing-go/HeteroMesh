package com.heteromesh.transport;

import com.heteromesh.protocol.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatIntegrationTest {

    @Test
    void shouldKeepConnectionAliveViaHeartbeat() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        CountDownLatch serverReceivedRequest = new CountDownLatch(1);

        try {
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 2, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_REQUEST) {
                                                serverReceivedRequest.countDown();
                                                ctx.writeAndFlush(Message.createTaskResponse(
                                                        msg.getRequestId(), "pong"));
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            // 客户端：连接建立后立即发一条业务消息证明连通
            Channel clientChannel = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 2, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            ctx.writeAndFlush(Message.createTaskRequest("hello"));
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            // 不需要处理，心跳在背后维护
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync().channel();

            // 第一步：验证业务消息能到达（初始连通性）
            assertTrue(serverReceivedRequest.await(3, TimeUnit.SECONDS),
                    "服务端应该收到客户端的业务消息");

            // 第二步：等待超过心跳超时时间（2s × 3 = 6s），连接应仍存活
            Thread.sleep(6000);
            assertTrue(clientChannel.isActive(),
                    "有心跳维护时，连接应在空闲 6 秒后仍保持活跃");

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    @Test
    void shouldDetectClientDisconnect() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        CountDownLatch serverChannelClosed = new CountDownLatch(1);

        try {
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 2, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            // 不作任何回复
                                        }
                                    }
                            );
                            ch.closeFuture().addListener(f -> serverChannelClosed.countDown());
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            // 客户端不装心跳 Handler，收到 PING 也不回复 PONG
            new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            // 收到消息但不回复
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync();

            // 服务端应在 ~10s 内检测到心跳超时并关闭该 Channel
            boolean detected = serverChannelClosed.await(15, TimeUnit.SECONDS);
            assertTrue(detected, "服务端应在心跳超时后关闭无响应的客户端连接");

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    @Test
    void shouldExchangeBusinessMessagesWhileHeartbeatActive() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        CountDownLatch replyReceived = new CountDownLatch(1);
        AtomicReference<String> echoResult = new AtomicReference<>();

        try {
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 2, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_REQUEST) {
                                                ctx.writeAndFlush(Message.createTaskResponse(
                                                        msg.getRequestId(), "echo: " + msg.getBody()));
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 2, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            ctx.writeAndFlush(Message.createTaskRequest("你好"));
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_RESPONSE) {
                                                echoResult.set(msg.getBody());
                                                replyReceived.countDown();
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync();

            // 等待业务消息往返
            assertTrue(replyReceived.await(5, TimeUnit.SECONDS),
                    "客户端应该收到服务端的业务回复");
            assertEquals("echo: 你好", echoResult.get(),
                    "回复内容应该匹配");

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}

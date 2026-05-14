package com.heteromesh.worker;

import com.heteromesh.protocol.*;
import com.heteromesh.transport.RpcClient;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RpcIntegrationTest {

    @Test
    void shouldEchoMessageEndToEnd() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        AtomicReference<String> echoResult = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            // 启动 Controller 端（Server）
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new com.heteromesh.transport.ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new com.heteromesh.transport.HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_REQUEST) {
                                                ctx.writeAndFlush(Message.createTaskResponse(
                                                        msg.getRequestId(),
                                                        "echo: " + msg.getBody()));
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            // 启动 Worker 端（Client）
            Channel clientChannel = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            RpcClient rpcClient = new RpcClient(ch);
                            ch.pipeline().addLast(
                                    new com.heteromesh.transport.ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new com.heteromesh.transport.HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            rpcClient.call("你好，RPC！")
                                                    .thenAccept(response -> {
                                                        echoResult.set(response.getBody());
                                                        latch.countDown();
                                                    });
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_RESPONSE) {
                                                rpcClient.onResponse(msg);
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync().channel();

            assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "应该在 5 秒内收到响应");
            assertEquals("echo: 你好，RPC！", echoResult.get(),
                    "响应内容应该匹配");

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    @Test
    void shouldHandleMultipleSequentialCalls() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        AtomicReference<String> r1 = new AtomicReference<>();
        AtomicReference<String> r2 = new AtomicReference<>();
        AtomicReference<String> r3 = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(3);

        try {
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new com.heteromesh.transport.ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new com.heteromesh.transport.HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_REQUEST) {
                                                ctx.writeAndFlush(Message.createTaskResponse(
                                                        msg.getRequestId(), "result: " + msg.getBody()));
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
                            RpcClient rpcClient = new RpcClient(ch);
                            ch.pipeline().addLast(
                                    new com.heteromesh.transport.ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new com.heteromesh.transport.HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            rpcClient.call("A").thenAccept(r -> {
                                                r1.set(r.getBody());
                                                latch.countDown();
                                            });
                                            rpcClient.call("B").thenAccept(r -> {
                                                r2.set(r.getBody());
                                                latch.countDown();
                                            });
                                            rpcClient.call("C").thenAccept(r -> {
                                                r3.set(r.getBody());
                                                latch.countDown();
                                            });
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_RESPONSE) {
                                                rpcClient.onResponse(msg);
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync();

            assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "三个请求都应该在 5 秒内收到响应，剩余：" + latch.getCount());
            assertEquals("result: A", r1.get());
            assertEquals("result: B", r2.get());
            assertEquals("result: C", r3.get());

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}

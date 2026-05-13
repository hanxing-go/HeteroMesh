package com.heteromesh.worker;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageDecoder;
import com.heteromesh.protocol.MessageEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClientServerIntegrationTest {

    @Test
    void shouldExchangeMessageBetweenClientAndServer() throws Exception {
        CountDownLatch serverReceived = new CountDownLatch(1);
        CountDownLatch clientReceived = new CountDownLatch(1);
        AtomicReference<Message> receivedByServer = new AtomicReference<>();
        AtomicReference<Message> receivedByClient = new AtomicReference<>();

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        try {
            // 1. 启动测试服务器（管道与 HeteroMeshServer 一致）
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            receivedByServer.set(msg);
                                            serverReceived.countDown();
                                            // 回复客户端
                                            ctx.writeAndFlush(Message.createTaskResponse(
                                                    msg.getRequestId(), "你好，客户端！"));
                                        }
                                    }
                            );
                        }
                    })
                    .bind(0) // 端口 0 = 系统自动分配
                    .sync()
                    .channel();

            int actualPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            // 2. 启动客户端（管道与 WorkerClient 一致）
            new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            ctx.writeAndFlush(Message.createTaskRequest("你好，服务器！"));
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            receivedByClient.set(msg);
                                            clientReceived.countDown();
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", actualPort)
                    .sync();

            // 3. 等待消息交换完成（最多等 5 秒）
            assertTrue(serverReceived.await(5, TimeUnit.SECONDS), "服务器应该收到消息");
            assertTrue(clientReceived.await(5, TimeUnit.SECONDS), "客户端应该收到回复");

            // 4. 验证消息内容
            assertNotNull(receivedByServer.get());
            assertEquals("你好，服务器！", receivedByServer.get().getBody());

            assertNotNull(receivedByClient.get());
            assertEquals("你好，客户端！", receivedByClient.get().getBody());

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    @Test
    void shouldHandleMultipleMessages() throws Exception {
        CountDownLatch latch = new CountDownLatch(10);

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        try {
            // 服务器：收到后原样回复
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            ctx.writeAndFlush(Message.createTaskResponse(
                                                    msg.getRequestId(), "echo: " + msg.getBody()));
                                        }
                                    }
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            Channel clientChannel = new Bootstrap()
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
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            // 连续发送 10 条消息（测试粘包处理）
                                            for (int i = 0; i < 10; i++) {
                                                ctx.write(Message.createTaskRequest("msg-" + i));
                                            }
                                            ctx.flush();
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            latch.countDown();
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync().channel();

            // 等待收到 10 条回复
            assertTrue(latch.await(10, TimeUnit.SECONDS),
                    "应该收到所有 10 条回复，剩余: " + latch.getCount());

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}

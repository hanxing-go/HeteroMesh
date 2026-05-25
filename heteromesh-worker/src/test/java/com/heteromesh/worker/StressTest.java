package com.heteromesh.worker;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageDecoder;
import com.heteromesh.protocol.MessageEncoder;
import com.heteromesh.protocol.MessageType;
import com.heteromesh.transport.ExceptionHandler;
import com.heteromesh.transport.HeartbeatHandler;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;


public class StressTest {
    @Test
    void shouldHandle1000Messages() throws Exception {

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);


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

            // 用 CountDownLatch(1000) 计数
            CountDownLatch latch = new CountDownLatch(1000);

            // 启动 Worker 端（Client）
            Channel clientChannel = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            RpcClient rpcClient = new RpcClient(ch);
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    // 4. 循环 1000 次 call("msg-" + i)，每次 thenAccept 里 latch.countDown()
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            for (int i = 0; i < 1000; i++) {
                                                rpcClient.call("msg-" + i + "你好，RPC！")
                                                        .thenAccept(response -> {
                                                            latch.countDown();
                                                        });
                                            }
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

            // latch.await(30, SECONDS)
            assertTrue(latch.await(10, TimeUnit.SECONDS),
                    "应该在 1 秒内收到响应");
            // assertEquals(0, latch.getCount())
            assertEquals(0, latch.getCount());

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

}

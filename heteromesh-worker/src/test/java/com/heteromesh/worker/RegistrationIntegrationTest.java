package com.heteromesh.worker;

import com.heteromesh.controller.ServerHandler;
import com.heteromesh.controller.node.NodeChannelMap;
import com.heteromesh.protocol.MessageDecoder;
import com.heteromesh.protocol.MessageEncoder;
import com.heteromesh.registry.InMemoryServiceRegistry;
import com.heteromesh.registry.ServiceRegistry;
import com.heteromesh.transport.ExceptionHandler;
import com.heteromesh.transport.HeartbeatHandler;
import com.heteromesh.transport.RpcClient;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationIntegrationTest {

    // 端到端：Worker 连接 → 发 REGISTER → Controller 注册 → Worker 收到 ACK
    @Test
    void shouldCompleteRegistrationFlow() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        try {
            ServiceRegistry registry = new InMemoryServiceRegistry();
            NodeChannelMap nodeChannelMap = new NodeChannelMap();

            // 启动 Controller（使用生产 ServerHandler）
            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
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
                                    new ServerHandler(registry, nodeChannelMap)
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            // 启动 Worker（使用生产 ClientHandler，自动发 REGISTER）
            new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
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
                                    new ClientHandler(rpcClient)
                            );
                        }
                    })
                    .connect("localhost", port).sync();

            // 等待注册完成（本地回环，1 秒足够）
            Thread.sleep(1000);

            assertEquals(1, registry.size());
            assertNotNull(registry.lookup("worker-gpu-01"));

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    // Worker 主动断开 → channelInactive 触发 → Controller 清理注册信息
    @Test
    void shouldUnregisterOnDisconnect() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(4);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        try {
            ServiceRegistry registry = new InMemoryServiceRegistry();
            NodeChannelMap nodeChannelMap = new NodeChannelMap();

            Channel serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
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
                                    new ServerHandler(registry, nodeChannelMap)
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            Channel clientChannel = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
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
                                    new ClientHandler(rpcClient)
                            );
                        }
                    })
                    .connect("localhost", port).sync().channel();

            // 等待注册完成
            Thread.sleep(500);
            assertEquals(1, registry.size());

            // Worker 主动断开
            clientChannel.close().sync();
            Thread.sleep(500);

            // Controller 的 channelInactive 清理了注册信息和映射
            assertEquals(0, registry.size());
            assertEquals(0, nodeChannelMap.size());

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}

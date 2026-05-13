package com.heteromesh.controller;

import com.heteromesh.protocol.MessageDecoder;
import com.heteromesh.protocol.MessageEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class HeteroMeshServer {
    private int port;// 监听的窗口

    public HeteroMeshServer(int port) {
        this.port =port;
    }

    public void run() throws Exception {
        //多线程时间循环，NioEventLoopGroup是一个多线程实践循环，负责I/0操作。
        // 两个线程组，接收客户端连接，处理连接的数据读写
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workGroup = new NioEventLoopGroup(4);

        try {
            new ServerBootstrap()
                    .group(bossGroup, workGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {

                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline().addLast(
                                    //1. 切包+解码+编码
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    // 2. 业务处理
                                    new ServerHandler()
                            );
                        }
                    })
                    .bind(port)
                    .sync()
                    .channel()
                    .closeFuture()
                    .sync();
        } finally {
            bossGroup.shutdownGracefully();
            workGroup.shutdownGracefully();
        }
    }
}

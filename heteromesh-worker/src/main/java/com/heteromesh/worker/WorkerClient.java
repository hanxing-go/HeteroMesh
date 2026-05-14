package com.heteromesh.worker;

import com.heteromesh.protocol.MessageDecoder;
import com.heteromesh.protocol.MessageEncoder;
import com.heteromesh.transport.ExceptionHandler;
import com.heteromesh.transport.HeartbeatHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

public class WorkerClient {
    private String host;
    private int port;
    public WorkerClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws InterruptedException {
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            Bootstrap b = new Bootstrap()
                    .group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler((new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline()
                                    .addLast(
                                            // 自然处理
                                            new ExceptionHandler(),
                                            // 心跳检测
                                            new IdleStateHandler(0,0,10, TimeUnit.SECONDS),
                                            //解包和拆包
                                            new MessageDecoder(),
                                            new MessageEncoder(),
                                            new HeartbeatHandler(),
                                            // 业务代码
                                            new ClientHandler()
                                    );
                        }
                    }));

            // 启动服务
            ChannelFuture f = b.connect(host, port).sync();

            // 等待连接关闭
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
        }
    }


}

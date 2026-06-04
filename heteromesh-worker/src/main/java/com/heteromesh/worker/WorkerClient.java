package com.heteromesh.worker;

import com.heteromesh.protocol.MessageDecoder;
import com.heteromesh.protocol.MessageEncoder;
import com.heteromesh.rpc.RpcDispatcher;
import com.heteromesh.rpc.RpcServiceRegistry;
import com.heteromesh.transport.ExceptionHandler;
import com.heteromesh.transport.HeartbeatHandler;
import com.heteromesh.transport.RpcClient;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class WorkerClient {
    private String host;
    private int port;
    private String nodeId;

    // 服务
    private RpcServiceRegistry serviceRegistry;

    // 任务调度服务
    private TaskExecutor executor;

    public WorkerClient(String host, int port, String nodeId) {
        this.host = host;
        this.port = port;
        this.nodeId = nodeId;
        this.serviceRegistry = new RpcServiceRegistry();

        executor = new DefaultTaskExecutor(nodeId);
    }

    public RpcServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    // 便捷方法：直接发布服务  ← 新增方法
    public <T> void publishService(Class<T> interfaceClass, T implementation) {
        serviceRegistry.publish(interfaceClass, implementation);
    }

    public void connect() throws InterruptedException {
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            log.info("连接启动");

            RpcDispatcher dispatcher = new RpcDispatcher(serviceRegistry);

            Bootstrap b = new Bootstrap()
                    .group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler((new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            RpcClient rpcClient = new RpcClient(socketChannel);
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
                                            new ClientHandler(rpcClient, nodeId, dispatcher, executor)
                                    );
                        }
                    }));

            // 启动服务
            ChannelFuture f = b.connect(host, port).sync();

            // 等待连接关闭
            f.channel().closeFuture().sync();
        } finally {
            log.info("连接断开");
            workerGroup.shutdownGracefully();
        }
    }


}

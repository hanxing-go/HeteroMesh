package com.heteromesh.demo;

import com.heteromesh.rpc.RpcProxy;
import com.heteromesh.transport.RpcClient;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * RPC Client 演示 — 通过动态代理调远程服务。
 * <p>
 * 启动顺序：
 * ① 先启动 Controller（HeteroMeshServer）
 * ② 再启动 DemoServer（Worker，发布 TaskService）
 * ③ 最后启动本类
 */
public class DemoClient {

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);

        // 用数组做 holder，解决 RpcClient 需要在 ChannelInitializer 里创建，
        // 但又需要在 connect() 之后拿到引用的问题
        RpcClient[] holder = new RpcClient[1];

        try {
            // ① 建立到 Controller 的 Netty 连接
            Channel channel = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            RpcClient rpcClient = new RpcClient(ch);
                            holder[0] = rpcClient;  // 存下来，connect() 之后用

                            ch.pipeline().addLast(
                                    new com.heteromesh.transport.ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new com.heteromesh.protocol.MessageDecoder(),
                                    new com.heteromesh.protocol.MessageEncoder(),
                                    new com.heteromesh.transport.HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<com.heteromesh.protocol.Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx,
                                                                     com.heteromesh.protocol.Message msg) {
                                            if (msg.getType() == com.heteromesh.protocol.MessageType.TASK_RESPONSE) {
                                                holder[0].onResponse(msg);
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", 9090).sync().channel();

            // ② 拿到代理对象 ← 服务引用
            TaskService service = RpcProxy.reference(TaskService.class, holder[0]);

            // ③ 像调本地方法一样调用！
            System.out.println(">>> 调用 processImage...");
            String result1 = service.processImage("/data/images/cat.jpg");
            System.out.println("结果1: " + result1);

            System.out.println(">>> 调用 getStatus...");
            String result2 = service.getStatus("task-001");
            System.out.println("结果2: " + result2);

            System.out.println(">>> 全部调用完成！");

        } finally {
            group.shutdownGracefully();
        }
    }
}

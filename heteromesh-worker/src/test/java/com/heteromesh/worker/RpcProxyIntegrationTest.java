package com.heteromesh.worker;

import com.heteromesh.protocol.*;
import com.heteromesh.rpc.RpcDispatcher;
import com.heteromesh.rpc.RpcProxy;
import com.heteromesh.rpc.RpcRequest;
import com.heteromesh.rpc.RpcResponse;
import com.heteromesh.rpc.RpcServiceRegistry;
import com.heteromesh.rpc.RpcStatus;
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

import com.google.gson.Gson;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试：Client 代理 → 网络 → Server（RpcDispatcher）→ 反射执行 → 返回。
 */
class RpcProxyIntegrationTest {

    private static final Gson GSON = new Gson();

    // ---------- 端到端：同步调用 ----------

    // 验证：起真实 Netty Server + Client，客户端通过 RpcProxy 调 greet("World")，
    // 服务器反射执行 TestServiceImpl.greet() 并返回 "Hello, World!"，代理同步等待并拿到结果
    @Test
    void shouldCallRemoteMethodViaProxy() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        // 准备 Server 端的服务注册
        RpcServiceRegistry registry = new RpcServiceRegistry();
        registry.publish(TestService.class, new TestServiceImpl());
        RpcDispatcher dispatcher = new RpcDispatcher(registry);

        // 用数组做 holder：RpcClient 在 ChannelInitializer 里创建，主线程通过 holder 拿到引用
        RpcClient[] clientHolder = new RpcClient[1];
        CountDownLatch connected = new CountDownLatch(1);

        try {
            // ① 启动 Server
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
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_REQUEST) {
                                                try {
                                                    RpcRequest request = GSON.fromJson(msg.getBody(), RpcRequest.class);
                                                    Object result = dispatcher.dispatch(request.getInvocation());
                                                    RpcResponse rpcResp = RpcResponse.success(result);
                                                    ctx.writeAndFlush(Message.createTaskResponse(
                                                            msg.getRequestId(), GSON.toJson(rpcResp)));
                                                } catch (Exception e) {
                                                    RpcResponse rpcResp = RpcResponse.error(RpcStatus.ERROR, e.getMessage());
                                                    ctx.writeAndFlush(Message.createTaskResponse(
                                                            msg.getRequestId(), GSON.toJson(rpcResp)));
                                                }
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .bind(0).sync().channel();

            int port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

            // ② Client 端：连接，然后在主线程里调 RPC（不能在 Netty I/O 线程里调！）
            Channel clientChannel = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            RpcClient rpcClient = new RpcClient(ch);
                            clientHolder[0] = rpcClient;
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            connected.countDown();  // 通知主线程：连接就绪
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_RESPONSE) {
                                                clientHolder[0].onResponse(msg);
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync().channel();

            // 等连接就绪
            assertTrue(connected.await(5, TimeUnit.SECONDS), "连接应在 5 秒内就绪");

            // ③ 在主线程里调 RPC（不在 I/O 线程里阻塞）
            TestService service = RpcProxy.reference(TestService.class, clientHolder[0]);
            String result = service.greet("World");

            assertEquals("Hello, World!", result);

            clientChannel.close().sync();

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    // ---------- 端到端：多参数调用 ----------

    // 验证：多参数方法（String + int）在 Gson 反序列化后参数类型正确，
    // RpcDispatcher.convertArgs() 能将 Double 转回 int，避免反射调用时参数类型不匹配
    @Test
    void shouldCallMethodWithMultipleParameters() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        EventLoopGroup clientGroup = new NioEventLoopGroup(1);

        RpcServiceRegistry registry = new RpcServiceRegistry();
        registry.publish(TestService.class, new TestServiceImpl());
        RpcDispatcher dispatcher = new RpcDispatcher(registry);

        RpcClient[] clientHolder = new RpcClient[1];
        CountDownLatch connected = new CountDownLatch(1);

        try {
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
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_REQUEST) {
                                                try {
                                                    RpcRequest request = GSON.fromJson(msg.getBody(), RpcRequest.class);
                                                    Object result = dispatcher.dispatch(request.getInvocation());
                                                    RpcResponse rpcResp = RpcResponse.success(result);
                                                    ctx.writeAndFlush(Message.createTaskResponse(
                                                            msg.getRequestId(), GSON.toJson(rpcResp)));
                                                } catch (Exception e) {
                                                    RpcResponse rpcResp = RpcResponse.error(RpcStatus.ERROR, e.getMessage());
                                                    ctx.writeAndFlush(Message.createTaskResponse(
                                                            msg.getRequestId(), GSON.toJson(rpcResp)));
                                                }
                                            }
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
                            RpcClient rpcClient = new RpcClient(ch);
                            clientHolder[0] = rpcClient;
                            ch.pipeline().addLast(
                                    new ExceptionHandler(),
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    new HeartbeatHandler(),
                                    new SimpleChannelInboundHandler<Message>() {
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) {
                                            connected.countDown();
                                        }

                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                                            if (msg.getType() == MessageType.TASK_RESPONSE) {
                                                clientHolder[0].onResponse(msg);
                                            }
                                        }
                                    }
                            );
                        }
                    })
                    .connect("localhost", port).sync().channel();

            assertTrue(connected.await(5, TimeUnit.SECONDS), "连接应在 5 秒内就绪");

            TestService service = RpcProxy.reference(TestService.class, clientHolder[0]);
            String result = service.describe("TaskService", 3);

            assertEquals("服务 TaskService 有 3 个方法", result);

            clientChannel.close().sync();

        } finally {
            clientGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    // ---------- 辅助接口和实现 ----------

    public interface TestService {
        String greet(String name);
        String describe(String serviceName, int methodCount);
    }

    public static class TestServiceImpl implements TestService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Override
        public String describe(String serviceName, int methodCount) {
            return "服务 " + serviceName + " 有 " + methodCount + " 个方法";
        }
    }
}

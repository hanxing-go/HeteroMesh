package com.heteromesh.controller;

import com.heteromesh.config.ConfigLoader;
import com.heteromesh.controller.node.DeadNodeDetector;
import com.heteromesh.controller.node.NodeChannelMap;
import com.heteromesh.controller.scheduler.TaskScheduler;
import com.heteromesh.controller.task.TaskTimeoutManager;
import com.heteromesh.loadbalancer.LoadBalancer;
import com.heteromesh.loadbalancer.LoadBalancerFactory;
import com.heteromesh.protocol.MessageDecoder;
import com.heteromesh.protocol.MessageEncoder;
import com.heteromesh.registry.InMemoryServiceRegistry;
import com.heteromesh.registry.ServiceRegistry;
import com.heteromesh.task.InMemoryTaskStore;
import com.heteromesh.task.TaskStore;
import com.heteromesh.transport.ExceptionHandler;
import com.heteromesh.transport.HeartbeatHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
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
            ServiceRegistry sr = new InMemoryServiceRegistry();
            NodeChannelMap ncm = new NodeChannelMap();


//            LoadBalancer lb = new ConsistentHashLoadBalancer();
            // ---------从 YAML 配置读取负载均衡策略名，默认 consistentHash---------------
            String lbType = "consistentHash";
            try {
                Map<String, Object> config = ConfigLoader.getConfig();
                Object heteromesh = config.get("heteromesh");
                if (heteromesh instanceof Map) {
                    Object lb = ((Map<String, Object>) heteromesh).get("loadbalancer");
                    if (lb instanceof Map) {
                        Object def = ((Map<String, Object>) lb).get("default");
                        if (def != null) {
                            lbType = def.toString();
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("读取负载均衡配置失败，使用默认值: consistentHash", e);
            }
            LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer(lbType);
            log.info("负载均衡策略: {}", loadBalancer.name());
//            ---------------------------------------------


            Map<String, Channel> pendingClients = new ConcurrentHashMap<>();
            // 启动注册检测
            new DeadNodeDetector(sr, ncm, loadBalancer, 30000).start();

            // 任务调度器
            TaskStore taskStore = new InMemoryTaskStore();
            TaskTimeoutManager taskTimeoutManager = new TaskTimeoutManager(taskStore);
            TaskScheduler scheduler = new TaskScheduler(taskStore, sr, loadBalancer, taskTimeoutManager);

            // 启动服务
            log.info("启动服务");
            new ServerBootstrap()
                    .group(bossGroup, workGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {

                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline().addLast(
                                    // 异常处理放在最前面
                                    new ExceptionHandler(),
                                    //心跳检测
                                    new IdleStateHandler(0, 0, 10, TimeUnit.SECONDS),
                                    //1. 切包+解码+编码
                                    new MessageDecoder(),
                                    new MessageEncoder(),
                                    // 心跳处理
                                    new HeartbeatHandler(),
                                    // 2. 业务处理
                                    new ServerHandler(sr, ncm, loadBalancer, pendingClients, scheduler, taskStore)
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
            log.info("服务关闭");
        }
    }
}

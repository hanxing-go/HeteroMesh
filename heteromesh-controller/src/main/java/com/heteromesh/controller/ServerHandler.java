package com.heteromesh.controller;

import com.google.gson.Gson;
import com.heteromesh.controller.node.NodeChannelMap;
import com.heteromesh.loadbalancer.LoadBalancer;
import com.heteromesh.protocol.Message;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j

public class ServerHandler extends SimpleChannelInboundHandler<Message> {
    private final ServiceRegistry registry;
    private final NodeChannelMap nodeChannelMap;
    private final LoadBalancer loadBalancer;
    private final Map<String, Channel> pendingClients;

    public ServerHandler(ServiceRegistry registry, NodeChannelMap nodeChannelMap,
                         LoadBalancer loadBalancer, Map<String, Channel> pendingClients) {
        this.registry = registry;
        this.nodeChannelMap = nodeChannelMap;
        this.loadBalancer = loadBalancer;
        this.pendingClients = pendingClients;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, Message message) throws Exception {
        // 任何消息到达都说明 Worker 还活着，更新心跳时间
        String nodeId = nodeChannelMap.getNodeId(channelHandlerContext.channel());
        if (nodeId != null) {
            ServiceInstance node = registry.lookup(nodeId);
            if (node != null) {
                node.setLastHeartbeat(System.currentTimeMillis());
            }
        }

        switch (message.getType()) {
            case REGISTER -> handleRegister(channelHandlerContext, message);
            case TASK_REQUEST -> handleTaskRequest(channelHandlerContext, message);
            case TASK_RESPONSE -> handleTaskResponse(channelHandlerContext, message);
            default -> log.debug("收到消息: type = {}", message.getType());
        }
    }

    private void handleTaskResponse(ChannelHandlerContext ctx, Message msg) {
        // Worker 处理完了任务，把 TASK_RESPONSE 发回了 Controller
        // Controller 需要把这个响应回传给最初发 TASK_REQUEST 的 Client
        String requestId = msg.getRequestId();
        Channel clientChannel = pendingClients.remove(requestId);

        if (clientChannel != null && clientChannel.isActive()) {
            clientChannel.writeAndFlush(msg);
            log.info("响应回传： requestId = {} -> Client", requestId);
        } else {
            log.warn("找不到原始客户端或已断开: requestId = {}", requestId);
        }
    }

    private void handleTaskRequest(ChannelHandlerContext ctx, Message msg) {
        /*
        * 修改原本的简单回复为任务分发*/
        // 一致性哈希：计算任务分配到哪个worker
        String requestId = msg.getRequestId();
        ServiceInstance target = loadBalancer.select(requestId);
        if (target == null) {
            // 没有可用 Worker
            log.warn("无可用节点处理任务: requestId={}", requestId);
            Message reply = Message.createTaskResponse(requestId, "无可用节点");
            ctx.writeAndFlush(reply);
            return;
        }

        // ② 从 NodeChannelMap 拿到目标 Worker 的 Channel
        Channel workerChannel = nodeChannelMap.getChannel(target.getNodeId());
        if (workerChannel == null || !workerChannel.isActive()) {
            log.warn("目标 Worker Channel 不可用: nodeId={}", target.getNodeId());
            Message reply = Message.createTaskResponse(requestId, "目标节点离线");
            ctx.writeAndFlush(reply);
            return;
        }

        // ③ 记录 requestId → 原始客户端 Channel（Worker 回复后要用）
        pendingClients.put(requestId, ctx.channel());

        // ④ 转发 TASK_REQUEST 到选中的 Worker
        workerChannel.writeAndFlush(msg);
        log.info("任务转发: requestId={} → Worker[{}]", requestId, target.getNodeId());
    }

    // 注册节点
    private void handleRegister(ChannelHandlerContext ctx, Message msg) {
        ServiceInstance instance = new Gson().fromJson(msg.getBody(), ServiceInstance.class);
        // 注册，存入注册中心
        registry.register(instance);
        // 双向绑定
        nodeChannelMap.bind(instance.getNodeId(), ctx.channel());
        // 打印日志
        log.info("节点注册成功, nodeId = {}, gpuType = {}, vramFree = {}MB",
                instance.getNodeId(), instance.getGpuType(), instance.getVramFree());
        // ------------- 加入一致性哈希环 --------------
        loadBalancer.addNode(instance);
        // 进行回复
        Message ack = Message.createAck(msg.getRequestId(), instance.getNodeId());
        ctx.writeAndFlush(ack);
        log.info("发送注册确认: nodeId={}", instance.getNodeId());
    }

    // 处理正常断连，进程退出情况
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 连接断开 ——> 清理
        String nodeId = nodeChannelMap.getNodeId(ctx.channel());
        if (nodeId != null) {
            registry.unregister(nodeId);
            // 也要从哈希环中移除
            loadBalancer.removeNode(nodeId);
            log.warn("节点 {} 断开", nodeId);
        }
        nodeChannelMap.unbind(ctx.channel());

        // 清理该 Channel 关联的待回传请求
        pendingClients.values().removeIf(ch -> ch == ctx.channel());
     }

}

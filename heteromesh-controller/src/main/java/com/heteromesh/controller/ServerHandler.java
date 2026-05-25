package com.heteromesh.controller;

import com.google.gson.Gson;
import com.heteromesh.controller.node.NodeChannelMap;
import com.heteromesh.protocol.Message;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j

public class ServerHandler extends SimpleChannelInboundHandler<Message> {
    private final ServiceRegistry registry;
    private final NodeChannelMap nodeChannelMap;

    public ServerHandler(ServiceRegistry registry, NodeChannelMap nodeChannelMap) {
        this.registry = registry;
        this.nodeChannelMap = nodeChannelMap;
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
            default -> log.debug("收到消息: type = {}", message.getType());
        }
    }

    private void handleTaskRequest(ChannelHandlerContext ctx, Message msg) {
        // 暂时保留原有逻辑，简单的进行回复
        log.info("收到消息: body={}", msg.getBody());
        Message reply = Message.createTaskResponse(msg.getRequestId(),"成功收到消息");
        ctx.writeAndFlush(reply);
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
            log.warn("节点 {} 断开", nodeId);
        }
        nodeChannelMap.unbind(ctx.channel());
     }

}

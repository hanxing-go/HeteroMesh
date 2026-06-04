package com.heteromesh.worker;

import com.google.gson.Gson;
import com.heteromesh.protocol.Message;

import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.rpc.*;
import com.heteromesh.task.TaskPayloadCodec;
import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;
import com.heteromesh.transport.RpcClient;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientHandler extends SimpleChannelInboundHandler<Message> {

    private final RpcClient rpcClient;
    private final String nodeId;
    private final RpcDispatcher dispatcher;
    private final Gson GSON = new Gson();

    private final TaskExecutor taskExecutor;


    public ClientHandler(RpcClient client, String nodeId, RpcDispatcher dispatcher, TaskExecutor taskExecutor) {
        this.rpcClient = client;
        this.nodeId = nodeId;
        this.dispatcher = dispatcher;

        this.taskExecutor = taskExecutor;
    }
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 连接建立后，发送注册消息
        ServiceInstance self = new ServiceInstance(
                this.nodeId, "127.0.0.1", 9090,
                "RTX 5090", 16 * 1024,
                100,
                System.currentTimeMillis(), System.currentTimeMillis()
        );

        Message registerMsg = Message.createRegister(self);
        ctx.writeAndFlush(registerMsg);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {

        switch (msg.getType()) {
            case TASK_RESPONSE -> rpcClient.onResponse(msg);
            case TASK_REQUEST -> handleTaskRequest(ctx, msg);
            case TASK_SUBMIT -> handleTaskSubmit(ctx, msg);
            case REGISTER_ACK -> log.info("注册确认: {}", msg.getBody());
            default -> log.debug("收到回复: {}", msg.getBody());
        }
    }

    private void handleTaskSubmit(ChannelHandlerContext ctx, Message msg) {
        TaskRequest request = TaskPayloadCodec.decodeRequest(msg.getBody());
        TaskResult result = taskExecutor.execute(request);

        Message response = Message.createTaskResult(msg.getRequestId(),
                TaskPayloadCodec.encodeResult(result));

        ctx.writeAndFlush(response);
    }

    private void handleTaskRequest(ChannelHandlerContext ctx, Message msg) {
        try {
            // ① 解析 RpcRequest
            RpcRequest request = GSON.fromJson(msg.getBody(), RpcRequest.class);

            // ② 走拦截器链
            RpcInterceptorChain chain = RpcInterceptorChain.newBuilder()
                    .addInterceptor(new LogInterceptor())
                    .handler(invocation -> dispatcher.dispatch(invocation))
                    .build();
            Object result = chain.next(request.getInvocation());// 走拦截器

            // ③ 构造成功响应
            RpcResponse rpcResponse = RpcResponse.success(result);
            Message response = Message.createTaskResponse(msg.getRequestId(), GSON.toJson(rpcResponse));
            ctx.writeAndFlush(response);

        } catch (IllegalArgumentException e) {
            // 服务未找到 / 方法未找到
            RpcStatus status = e.getMessage() != null && e.getMessage().contains("服务未找到")
                    ? RpcStatus.NOT_FOUND : RpcStatus.METHOD_NOT_FOUND;
            RpcResponse rpcResponse = RpcResponse.error(status, e.getMessage());
            Message response = Message.createTaskResponse(msg.getRequestId(), GSON.toJson(rpcResponse));
            ctx.writeAndFlush(response);

        } catch (Exception e) {
            log.error("RPC 调用失败: requestId = {}", msg.getRequestId(), e);
            RpcResponse rpcResponse = RpcResponse.error(RpcStatus.ERROR, e.getMessage());
            Message response = Message.createTaskResponse(msg.getRequestId(), GSON.toJson(rpcResponse));
            ctx.writeAndFlush(response);
        }
    }
}

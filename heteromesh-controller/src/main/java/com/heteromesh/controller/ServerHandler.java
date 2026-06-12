package com.heteromesh.controller;

import com.google.gson.Gson;
import com.heteromesh.controller.node.NodeChannelMap;
import com.heteromesh.controller.scheduler.ScheduleResult;
import com.heteromesh.controller.scheduler.TaskRetryPolicy;
import com.heteromesh.controller.scheduler.TaskScheduler;
import com.heteromesh.loadbalancer.LoadBalancer;
import com.heteromesh.protocol.Message;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import com.heteromesh.rpc.CircuitBreakerConfig;
import com.heteromesh.task.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j

public class ServerHandler extends SimpleChannelInboundHandler<Message> {
    private static final int DEFAULT_MAX_RETRIES = 2;   // 最多重试2次

    private static final long FORWARD_TIMEOUT_MS = 5_000;   // 转发超时 5s


    private final ServiceRegistry registry;
    private final NodeChannelMap nodeChannelMap;
    private final LoadBalancer loadBalancer;

    /*让Controller知道这个回复应该回送给谁，相当于快递中转站*/
    private final Map<String, Channel> pendingClients;

    /*熔断机制*/
    private final CircuitBreakerManager cbManager= new CircuitBreakerManager(new CircuitBreakerConfig());
    /*用来记住是哪个节点在处理请求*/
    private final Map<String, String> requestToNode = new ConcurrentHashMap<>();

    /*任务调度*/
    private final TaskScheduler taskScheduler;
    private final TaskStore taskStore;

    /*重试机制*/
    private final TaskRetryPolicy retryPolicy;


    public ServerHandler(ServiceRegistry registry, NodeChannelMap nodeChannelMap,
                         LoadBalancer loadBalancer,
                         Map<String, Channel> pendingClients,
                         TaskScheduler taskScheduler,
                         TaskStore taskStore) {
        // 旧构造器，默认不启用重试
        this(registry, nodeChannelMap, loadBalancer,
                pendingClients, taskScheduler, taskStore, new TaskRetryPolicy(1));
    }

    public ServerHandler(ServiceRegistry registry, NodeChannelMap nodeChannelMap,
                         LoadBalancer loadBalancer,
                         Map<String, Channel> pendingClients,
                         TaskScheduler taskScheduler,
                         TaskStore taskStore,
                         TaskRetryPolicy retryPolicy) {
        this.registry = registry;
        this.nodeChannelMap = nodeChannelMap;
        this.loadBalancer = loadBalancer;
        this.pendingClients = pendingClients;
        this.taskScheduler = taskScheduler;
        this.taskStore = taskStore;

        this.retryPolicy = retryPolicy;
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
            case TASK_SUBMIT -> handleTaskSubmit(channelHandlerContext, message);
            case TASK_RESULT -> handleTaskResult(channelHandlerContext, message);
            default -> log.debug("收到消息: type = {}", message.getType());
        }
    }

    private void handleTaskSubmit(ChannelHandlerContext ctx, Message message) {
//         从 msg.body 解析 TaskRequest
        TaskRequest request = TaskPayloadCodec.decodeRequest(message.getBody());
        ScheduleResult result = taskScheduler.schedule(request);

//         调用 taskScheduler.schedule(request)
//         如果失败：
//            - 构造 TaskResult(status=FAILED)
//                - createTaskResult(msg.requestId, resultJson)
//                - 直接 ctx.writeAndFlush
        if (!result.isSuccess()) {
            TaskResult failed = new TaskResult(
                    result.getTask().getTaskId(),
                    TaskStatus.FAILED,
                    null,
                    result.getErrorMessage(),
                    0,
                    System.currentTimeMillis(),
                    null
            );

            String replyBody = TaskPayloadCodec.encodeResult(failed);


            Message reply = Message.createTaskResult(message.getRequestId(), replyBody);
            ctx.writeAndFlush(reply);
            return;
        }


//         如果成功：
//            - 找到 worker Channel
        Channel workerChannel = nodeChannelMap.getChannel(result.getWorker().getNodeId());
        if (workerChannel == null || !workerChannel.isActive()) {
            // TODO: 本课先返回失败；后续第 21 课做任务级重试
            TaskResult failed = new TaskResult(
                    result.getTask().getTaskId(),
                    TaskStatus.FAILED,
                    null,
                    "Selected worker channel is not available: " + result.getWorker().getNodeId(),
                    0,
                    System.currentTimeMillis(),
                    result.getWorker().getNodeId()
            );
            taskStore.complete(failed.getTaskId(), failed);

            Message reply = Message.createTaskResult(
                    message.getRequestId(),
                    TaskPayloadCodec.encodeResult(failed)
            );

            ctx.writeAndFlush(reply);
            return;
        }


//            - 保存 pendingClients：requestId -> clientChannel
        pendingClients.put(message.getRequestId(), ctx.channel());
        //                - 转发原始 TASK_SUBMIT 给 Worker
        workerChannel.writeAndFlush(message);

    }

    private void handleTaskResult(ChannelHandlerContext ctx, Message message) {

        TaskResult result = TaskPayloadCodec.decodeResult(message.getBody());

        // 先转换为running状态
        TaskMetadata task = taskStore.get(result.getTaskId()).orElse(null);
        if (task != null && task.getStatus() == TaskStatus.DISPATCHING) {
            taskStore.updateStatus(result.getTaskId(), TaskStatus.RUNNING);
        }

        TaskResult finalResult = result;
        Message replyMessage = message;
        // 先判断是否需要重试
        if (task != null && retryPolicy.canRetry(task, result)) {
            ScheduleResult retryResult = taskScheduler.retry(result.getTaskId());

            if (retryResult.isSuccess()) {
                String retryWorkerId = retryResult.getWorker().getNodeId();

                Channel workerChannel = nodeChannelMap.getChannel(retryWorkerId);

                if (workerChannel != null && workerChannel.isActive()) {
                    TaskRequest request = retryResult.getTask().getRequest();

                    Message retryMessage = Message.createTaskSubmit(
                            message.getRequestId(),
                            TaskPayloadCodec.encodeRequest(request)
                    );

                    workerChannel.writeAndFlush(retryMessage);
                    return;
                }

                finalResult = new TaskResult(
                        result.getTaskId(),
                        TaskStatus.FAILED,
                        null,
                        "Retry worker channel is not available: " + retryWorkerId,
                        result.getStartedAt(),
                        System.currentTimeMillis(),
                        retryWorkerId
                );

                replyMessage = Message.createTaskResult(
                        message.getRequestId(),
                        TaskPayloadCodec.encodeResult(finalResult)
                );
            }
        }

        try {
            taskStore.complete(finalResult.getTaskId(), finalResult);
        } catch (IllegalStateException e) {
          log.warn("后到结果，不能覆盖终态: taskId={}, requestId={}",
                  finalResult.getTaskId(),replyMessage.getRequestId(), e);
        } catch (Exception e) {
            log.warn("更新任务结果失败: taskId={}, requestId={}",
                    finalResult.getTaskId(), replyMessage.getRequestId(), e);
        }

        Channel clientChannel = pendingClients.remove(replyMessage.getRequestId());

        if (clientChannel != null && clientChannel.isActive()) {
            clientChannel.writeAndFlush(replyMessage);
        } else {
            log.warn("找不到任务提交方: requestId={}", replyMessage.getRequestId());
        }
    }

    private void handleTaskResponse(ChannelHandlerContext ctx, Message msg) {
        // Worker 处理完了任务，把 TASK_RESPONSE 发回了 Controller
        // Controller 需要把这个响应回传给最初发 TASK_REQUEST 的 Client
        String requestId = msg.getRequestId();
        Channel clientChannel = pendingClients.remove(requestId);

        // 更新worker状态
        String nodeId = requestToNode.remove(requestId);
        if (nodeId != null) {
            cbManager.onSuccess(nodeId);
        }

        if (clientChannel != null && clientChannel.isActive()) {
            clientChannel.writeAndFlush(msg);
            log.info("响应回传： requestId = {} -> Client", requestId);
        } else {
            log.warn("找不到原始客户端或已断开或无法匹配原始请求: requestId = {}", requestId);
        }
    }

    private void handleTaskRequest(ChannelHandlerContext ctx, Message msg) {
        tryForward(ctx, msg, new HashSet<>(), 0);
    }

    private void tryForward(ChannelHandlerContext ctx, Message msg, Set<String> failedNodes, int attempt) {
        String requestId = msg.getRequestId();

        /*将所有已经熔断的节点添加到失败节点中*/
        failedNodes.addAll(cbManager.getOpenNodes());

        if (attempt > DEFAULT_MAX_RETRIES) {
            Message reply = Message.createTaskResponse(requestId, "重试" + DEFAULT_MAX_RETRIES + "次后仍失败");
            ctx.writeAndFlush(reply);
            return;
        }

        // 选一个worker
        ServiceInstance target = loadBalancer.select(requestId, failedNodes);
        if (target == null) {
            Message reply = Message.createTaskResponse(requestId, "当前没有节点可用");
            ctx.writeAndFlush(reply);
            return;
        }

        Channel workerChannel = nodeChannelMap.getChannel(target.getNodeId());
        if (workerChannel == null || !workerChannel.isActive()) {
            failedNodes.add(target.getNodeId());
            tryForward(ctx, msg, failedNodes, attempt);
            return;
        }

        // 转发
        pendingClients.put(requestId, ctx.channel());
        /* 记住是哪个节点处理的工作*/
        requestToNode.put(requestId, target.getNodeId());
        workerChannel.writeAndFlush(msg);

        // 设置超时
        ctx.executor().schedule(() -> {
            Channel client = pendingClients.remove(requestId);

            /* 如果client == null， 说明handleTaskResponse已经处理过了 -> 不需要作任何事*/
            if (client != null) {
                log.warn("Worker[{}] 超时: requestId={}, 重试第{}次",
                        target.getNodeId(), requestId, attempt + 1);

                /* 请求失败一次，该节点要记录失败次数*/
                cbManager.onFailure(target.getNodeId());
                /* 更新requestToNode*/
                requestToNode.remove(requestId);

                failedNodes.add(target.getNodeId());
                tryForward(ctx, msg, failedNodes, attempt + 1);
            }
        }, FORWARD_TIMEOUT_MS, TimeUnit.MILLISECONDS);


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

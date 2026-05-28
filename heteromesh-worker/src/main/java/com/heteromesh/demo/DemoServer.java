package com.heteromesh.demo;

import com.heteromesh.worker.WorkerClient;

/**
 * Demo Server — 启动一个 Worker，发布 TaskService，然后连接 Controller。
 * <p>
 * 启动顺序：
 * ① 先启动 Controller（HeteroMeshServer.main）
 * ② 再启动本类
 * ③ 最后启动 DemoClient
 */
public class DemoServer {

    public static void main(String[] args) throws Exception {
        WorkerClient worker = new WorkerClient("localhost", 9090, "worker-demo-01");

        // 发布服务：告诉框架「接口 TaskService 由这个对象来实现」
        worker.publishService(TaskService.class, new TaskServiceImpl());

        // 连接 Controller，自动注册 + 发心跳
        worker.connect();
    }
}

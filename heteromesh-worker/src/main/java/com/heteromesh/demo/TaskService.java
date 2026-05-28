package com.heteromesh.demo;

/**
 * 演示用服务接口 — Client 和 Worker 共享的「合同」。
 * Client 只看到这个接口，Worker 有真实实现。
 */
public interface TaskService {

    /** 处理图片，返回处理结果描述 */
    String processImage(String imagePath);

    /** 查询任务状态 */
    String getStatus(String taskId);
}

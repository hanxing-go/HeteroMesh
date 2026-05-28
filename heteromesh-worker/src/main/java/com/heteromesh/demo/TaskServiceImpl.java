package com.heteromesh.demo;

import lombok.extern.slf4j.Slf4j;

/**
 * TaskService 的真实实现 — 只在 Worker 端存在，Client 端没有这个类。
 * 方法体里可以放任何业务逻辑：调 AI 模型、查数据库、读文件……
 */
@Slf4j
public class TaskServiceImpl implements TaskService {

    @Override
    public String processImage(String imagePath) {
        log.info("[Worker 端] 处理图片: {}", imagePath);
        // 模拟处理（实际项目里这里调 AI 推理引擎）
        return "图片 " + imagePath + " 处理完成 [Worker 端执行]";
    }

    @Override
    public String getStatus(String taskId) {
        log.info("[Worker 端] 查询任务状态: {}", taskId);
        return "任务 " + taskId + " 状态: 已完成";
    }
}

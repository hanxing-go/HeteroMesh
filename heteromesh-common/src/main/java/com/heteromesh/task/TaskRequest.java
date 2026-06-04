package com.heteromesh.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 用户提交给 Controller 的任务请求。
 *
 * 当前阶段先不接真实 GPU 推理，payload 可以是一段字符串或 JSON。
 * resourceRequirements 用来为后续异构调度做准备。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRequest {
    private String taskId;                              // 任务唯一ID，可以由客户端传，也可以由Controller生成
    private String taskType;                            // 任务类型，比如TEXT_GENERATION、IMAGE_INFERENCE
    private String payload;                             // 任务输入
    private Map<String, String> resourceRequirements;   // 资源需求，比如GPU型号、显存下限
    private long timeoutMs;                             // 任务超过时间
}

package com.heteromesh.task;


import java.util.List;
import java.util.Optional;

/**
 * 任务仓库接口。
 *
 * 当前只有内存实现，后续可以替换成 MySQL / Redis / etcd。
 */
public interface TaskStore {
    /**
     * 创建并保存任务元数据。
     */
    TaskMetadata create(TaskRequest request);

    /**
     * 根据 taskId 查询任务。
     * Optional 专门治Java 空指针NPE，用来代替一堆if(obj != null)，规范空值处理
     */
    Optional<TaskMetadata> get(String taskId);

    /**
     * 更新任务状态。
     */
    void updateStatus(String taskId, TaskStatus status);

    /**
     * 绑定任务被派发到的 Worker。
     */
    void assignWorker(String taskId, String workerId);

    /**
     * 记录一次尝试过的 Worker，并增加 retryCount。
     */
    void recordAttempt(String taskId, String workerId);

    /**
     * 写入任务结果。
     */
    void complete(String taskId, TaskResult result);

    /**
     * 列出全部任务。
     */
    List<TaskMetadata> listAll();

    /**
     * 按状态过滤任务。
     */
    List<TaskMetadata> listByStatus(TaskStatus status);
}

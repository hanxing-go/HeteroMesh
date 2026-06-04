package com.heteromesh.controller.scheduler;

import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.task.TaskMetadata;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ScheduleResult {
    private boolean success;
    private TaskMetadata task;
    private ServiceInstance worker;
    private String errorMessage;

    public static ScheduleResult success(TaskMetadata task, ServiceInstance worker) {
        return new ScheduleResult(true, task, worker, null);
    }

    public static ScheduleResult failure(TaskMetadata task, String errorMessage) {
        return new ScheduleResult(false, task, null, errorMessage);
    }
}

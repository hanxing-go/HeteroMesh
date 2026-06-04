package com.heteromesh.task;

import com.google.gson.Gson;


public final class TaskPayloadCodec {
    private static final Gson GSON = new Gson();

    private TaskPayloadCodec() {

    }

    public static String encodeRequest(TaskRequest request) {
        return GSON.toJson(request);
    }
    public static TaskRequest decodeRequest(String body) {
        checkBody(body);
        return GSON.fromJson(body, TaskRequest.class);
    }

    public static String encodeResult(TaskResult result) {
        return GSON.toJson(result);
    }
    public static TaskResult decodeResult(String body) {
        checkBody(body);
        return GSON.fromJson(body, TaskResult.class);
    }

    private static void checkBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Task payload body must not be empty");
        }
    }
}

package com.heteromesh.transport;

import io.netty.channel.Channel;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接管理器 — 管理 Controller 端所有已连接的 Worker Channel
 *
 * 当前（第 3 课）：用 Channel.id 作为 key 管理连接
 * 未来（第 6 课）：切换为 getByNodeId(String nodeId)，nodeId 来自 Worker 注册消息
 */
public class ConnectionManager {

    private final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();

    public void add(Channel channel) {
        channels.put(channel.id().asShortText(), channel);
    }

    public void remove(Channel channel) {
        channels.remove(channel.id().asShortText());
    }

    public Channel get(String channelId) {
        return channels.get(channelId);
    }

    public Collection<Channel> getAll() {
        return channels.values();
    }

    public int size() {
        return channels.size();
    }
}

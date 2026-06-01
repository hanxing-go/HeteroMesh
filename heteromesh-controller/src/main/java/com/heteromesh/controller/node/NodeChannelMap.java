package com.heteromesh.controller.node;


import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentHashMap;

public class NodeChannelMap {
    // 建立nodeId和Netty Channel的双向映射
    private final ConcurrentHashMap<String, Channel> nodeToChannel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, String> channelToNode = new ConcurrentHashMap<>();

    public void bind(String nodeId, Channel channel) {
        // 如果该 nodeId 之前绑定过旧 Channel，先清理旧的逆向映射
        Channel oldChannel = nodeToChannel.put(nodeId, channel);
        if (oldChannel != null) {
            channelToNode.remove(oldChannel);
        }
        channelToNode.put(channel, nodeId);
    }

    public void unbind(Channel channel) {
        String nodeId = channelToNode.remove(channel);
        if (nodeId != null) {
            nodeToChannel.remove(nodeId);
        }
    }

    public Channel getChannel(String nodeId) {
        return nodeToChannel.get(nodeId);
    }

    public String getNodeId(Channel channel) {
        return channelToNode.get(channel);
    }

    public int size() {
        return nodeToChannel.size();
    }
}

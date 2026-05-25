package com.heteromesh.controller.node;


import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentHashMap;

public class NodeChannelMap {
    // 建立nodeId和Netty Channel的双向映射
    private final ConcurrentHashMap<String, Channel> nodeToChannel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> channelToNode = new ConcurrentHashMap<>();

    public void bind(String nodeId, Channel channel) {
        nodeToChannel.put(nodeId, channel);
        channelToNode.put(channel.id().asShortText(), nodeId);
    }

    public void unbind(Channel channel) {
        String nodeId = channelToNode.get(channel.id().asShortText());
        channelToNode.remove(channel.id().asShortText());
        if (nodeId != null) {
            nodeToChannel.remove(nodeId);
        }
    }

    public Channel getChannel(String nodeId) {
        return nodeToChannel.get(nodeId);
    }

    public String getNodeId(Channel channel) {
        return channelToNode.get(channel.id().asShortText());
    }

    public int size() {
        return nodeToChannel.size();
    }
}

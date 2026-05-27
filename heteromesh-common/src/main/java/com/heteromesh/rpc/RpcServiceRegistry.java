package com.heteromesh.rpc;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RpcServiceRegistry {

    private final Map<String, Object> services = new ConcurrentHashMap<>();

    /**
     * 发布一个服务。
     *
     * @param interfaceClass 服务接口（用于获取 serviceName）
     * @param implementation 实现对象
     */
    public <T> void publish(Class<T> interfaceClass, T implementation) {
        String serviceName = interfaceClass.getName();
        Object old = services.put(serviceName, implementation);

        if (old != null) {
            log.warn("服务重复发布，覆盖旧实现：serviceName = {}", serviceName);
        } else {
            log.info("服务发布成功: serviceName = {}", serviceName);
        }
    }

    /**
     * 查找服务实现。
     * @return 实现对象，未找到时返回 null
     */
    public Object lookup(String serviceName) {
        return services.get(serviceName);
    }

    /**
     * 取消发布。
     */
    public void unpublish(Class<?> interfaceClass) {
        services.remove(interfaceClass.getName());
    }

    public int size() {
        return services.size();
    }
}

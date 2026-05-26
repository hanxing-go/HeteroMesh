package com.heteromesh.loadbalancer;

import com.heteromesh.spi.SpiExtensionLoader;

public class LoadBalancerFactory {
    private static final SpiExtensionLoader<LoadBalancer> LOADER =
            SpiExtensionLoader.load(LoadBalancer.class);

    public static LoadBalancer getLoadBalancer(String name) {
        return LOADER.getExtension(name);
    }

    public static LoadBalancer getDefault() {
        return LOADER.getDefaultExtension();
    }
}

package com.heteromesh.rpc;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RpcInvocation {
    /* 接口全限定名，如："com.hetromesh.demo.TaskService"*/
    private String serviceName;

    /* 方法名: 如: ""pricessImage*/
    private String methodName;

    /* 参数类型全限定名数组，用于解决重载方法的路由*/
    private String[] parameterTypes;

    /*参数值数组（JSon 序列化后的原始值）*/
    private Object[] args;
}

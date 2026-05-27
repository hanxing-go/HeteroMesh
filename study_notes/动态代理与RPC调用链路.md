# 动态代理与 RPC 调用链路

## 动态代理的本质

JDK 动态代理 = JVM 在运行时凭空生成一个「实现了指定接口」的假对象。

```java
// 正常调用：你需要一个真实的实现类
Greeter g = new GreeterImpl();
String s = g.sayHello("张三");  // → 执行 GreeterImpl 里的代码

// 动态代理：JVM 帮你生成一个假对象
Greeter proxy = (Greeter) Proxy.newProxyInstance(
    Greeter.class.getClassLoader(),
    new Class[]{ Greeter.class },
    (proxyObj, method, methodArgs) -> {
        // 所有方法调用都进这里！
        // 你想干啥都行——打印日志、返回假数据、发网络请求……
        return "假的返回值！";
    }
);

String s = proxy.sayHello("张三");
// → 不执行 GreeterImpl
// → 执行的是 InvocationHandler 里的 invoke() 方法
// → invoke() 的返回值直接变成 sayHello() 的返回值
```

**调用方完全不知道这是一个假对象**——它实现了接口，有类型检查，IDE 能自动补全。

---

## 完整数据流动（一次 RPC 调用经过的所有步骤）

```
Client 端                                    Controller                    Worker 端
─────────                                    ──────────                    ─────────

// 你写的代码
TaskService proxy = RpcProxy.reference(...);
String r = proxy.processImage("cat.jpg");
    │
    ▼  JVM 自动跳转到 InvocationHandler
    │
┌───────────────────────────┐
│ InvocationHandler.invoke()│
│                           │
│ ① 打包成 RpcInvocation    │
│   serviceName:            │
│     "com.xxx.TaskService" │
│   methodName:             │
│     "processImage"        │
│   parameterTypes:         │
│     ["java.lang.String"]  │
│   args: ["cat.jpg"]       │
│                           │
│ ② Gson 序列化成 JSON      │
│                           │
│ ③ rpcClient.call(json)   │
│   → 发 TASK_REQUEST       │
└───────────┬───────────────┘
            │
    TASK_REQUEST ────────────→ ServerHandler ──────→ ClientHandler
            │                 handleTaskRequest()    handleTaskRequest()
            │                       │                      │
            │                 ① LB 选 Worker               │
            │                 ② 转发（不读 body）           │
            │                       │                      │
            │           ┌───────────┘                      │
            │           │                                  │
            │     TASK_REQUEST ───────────────────────────→│
            │                                              │
            │                              ┌───────────────┘
            │                              │
            │              dispatcher.dispatch(body)
            │                              │
            │                  ① fromJson() 还原 RpcInvocation
            │                  ② registry.lookup("TaskService")
            │                     → 找到 TaskServiceImpl 实例
            │                  ③ getMethod("processImage", String.class)
            │                     → 拿到 Method 对象
            │                  ④ method.invoke(service, "cat.jpg")
            │                     → 反射调用！
            │                              │
            │                     TaskServiceImpl.processImage("cat.jpg")
            │                              │
            │                     return "图片 cat.jpg 处理完成"
            │                              │
            │           ←─── TASK_RESPONSE ───────────────────┘
            │              body: "图片 cat.jpg 处理完成"
            │
    ← TASK_RESPONSE ─────────┘
            │
┌───────────┴───────────────┐
│ InvocationHandler.invoke()│  (接上面)
│                           │
│ ④ future.get() 等到回复   │
│   reply.body              │
│   = "图片 cat.jpg 处理完成"│
│                           │
│ ⑤ Gson.fromJson()        │
│   把 body 转成 String      │
│                           │
│ ⑥ return "图片 cat.jpg 处理完成"
└───────────────────────────┘
    │
    ▼
String r = "图片 cat.jpg 处理完成";  // ← 回到你的代码，跟调本地方法一模一样
```

---

## 类比：秘书

```
你（调用方）                秘书（代理对象）               远程（Worker）
───────────               ──────────────               ──────────────

"帮我查一下              收到指令：
 processImage             ① 记下方法名 + 参数
 参数 cat.jpg"            ② 打电话给远程
          ──────────────→ ③ 等回复              ─────→ 真正干活的人
                                                 ←───── 回复结果
                          ④ 记下回复
                          ⑤ 把结果告诉你
          ←──────────────
得到结果：                 
"图片 cat.jpg 处理完成"
```

你看到的是跟秘书说一句话、拿到答案。你不知道（也不需要知道）秘书具体是怎么打电话、怎么等的。

---

## 4 个核心文件及其职责

| 文件 | 一句话职责 | 位置 |
|------|-----------|------|
| `RpcInvocation` | 装数据的盒子：方法调用的全部信息（接口名、方法名、参数类型、参数值） | common |
| `RpcProxy` | 生成假对象：拦截方法调用 → 打包 → 发网络 → 等回复 → 返回结果 | common |
| `RpcServiceRegistry` | 一个 HashMap：`接口名 → 实现对象`，查到这个接口由谁实现 | common |
| `RpcDispatcher` | 收到请求后的执行者：解析 JSON → 查注册表 → 反射调用方法 → 返回结果 | common |

**哪个模块用哪个：**

```
heteromesh-common（共享）
  ├─ RpcInvocation       ← Client 打包用，Worker 解包用
  ├─ RpcProxy            ← Client 用
  ├─ RpcServiceRegistry  ← Worker 用
  └─ RpcDispatcher       ← Worker 用

heteromesh-worker（Worker 端）
  ├─ ClientHandler        ← 改：收到 TASK_REQUEST 时调 RpcDispatcher
  └─ WorkerClient         ← 改：暴露 publishService() 方法

heteromesh-controller（Controller）
  └─ 零改动 ← 只转发 TASK_REQUEST/TASK_RESPONSE，不读 body
```

---

## Controller 为什么不用改？

```
之前：body = "你好"
现在：body = '{"serviceName":"TaskService","methodName":"processImage",...}'

Controller 的代码：
  switch (msg.getType()) {
      case TASK_REQUEST -> handleTaskRequest(ctx, msg);
      ...
  }
  // ↑ 只判断消息类型，从不解析 body 内容
  // body 从 "你好" 变成 JSON，Controller 完全不关心
```

这就是**分层设计的价值**——传输层（Controller）只负责路由转发，应用层协议（RpcInvocation JSON）变化时传输层不用动。

---

## 参数类型列表为什么必要？

```java
// Java 允许方法重载：
interface TaskService {
    String process(String path);     // 方法 1：参数是 String
    String process(byte[] data);     // 方法 2：参数是 byte[]
}

// 如果没有 parameterTypes：
// Worker 收到 methodName="process", args=["cat.jpg"]
// → "cat.jpg" 既可以是 String 也可以是 byte[]
// → 不知道调哪个方法！

// 有了 parameterTypes：
// ["java.lang.String"] → 调方法 1
// ["byte[]"]           → 调方法 2
// 和编译器做重载决议是同一个道理
```

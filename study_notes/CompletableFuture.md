## CompletableFuture原理及应用场景详解

![CompletableFuture.png](https://ucc.alicdn.com/pic/developer-ecology/ab7ghvgbj22ju_88afb569fc6c4d848be134160264fb5a.png?x-oss-process=image/resize,w_1400/format,webp)

### 回调

不用原地死等结果，先去干别的，等异步跑完，自动执行一段代码，执行的代码就叫回调。

### Volatile关键字

1. 可见性

当一个线程修改了一个 volatile变量的值，其他线程能够立即看到修改后的值。

这是因为 volatile变量不会被缓存在寄存器或其他处理器不可见的地方，因此保证了每次读取 volatile变量都会从主内存中读取最新的值。

2. 有序性

volatile变量的读写操作具有一定的有序性，即禁止了指令重排序优化，就是禁止编译器自动重新排序。

这意味着，在一个线程中，对 volatile变量的写操作一定发生在后续对这个变量的读操作之前。

3. 使用场景
   volatile 关键字通常用于以下场景：

\* 当多个线程共享一个变量，并且至少有一个线程会修改这个变量时。
\* 当需要确保变量的修改对所有线程立即可见时。
\* 当变量的状态不需要依赖于之前的值，或者不需要与其他状态变量共同参与不变约束时。

5.使用注意事项

\* volatile关键字不能保证**原子性**。如果需要对变量进行复合操作（例如自增），则应该使用 `synchronized` 关键字或其他并发工具（如 `AtomicInteger`）来确保线程安全。
\* 过度使用 volatile可能会导致性能下降，因为它会禁止编译器和处理器对代码进行某些优化。因此，在使用 volatile时应该仔细考虑其必要性。

## CompletableFuture

CompletableFuture是java 8引入的一个类，是对Future的强化，实现了Future和CompletionStage接口，它可以帮助我们以异步的方式执行任务，并且提供了大量的方法来处理和控制这些任务的结果。




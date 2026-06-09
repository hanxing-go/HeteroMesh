# Java Stream 与方法引用

## 例子

```java
public List<TaskSummaryView> listAll() {
    return taskStore.listAll().stream()
            .map(this::toSummaryView)
            .toList();
}

public List<TaskSummaryView> listByStatus(TaskStatus status) {
    return taskStore.listByStatus(status).stream()
            .map(this::toSummaryView)
            .toList();
}
```

## 这段代码在做什么

这段代码的目标是：

```text
List<TaskMetadata> -> List<TaskSummaryView>
```

也就是把任务仓库里的内部任务元数据，转换成对外展示用的摘要视图。

## 逐段理解

```java
taskStore.listAll()
```

拿到所有任务，返回值类型是 `List<TaskMetadata>`。

```java
.stream()
```

把 `List` 变成 Stream 流水线。Stream 适合对一组数据做批量处理，比如转换、过滤、排序。

```java
.map(this::toSummaryView)
```

`map` 表示把每个元素转换成另一个元素。

这里每个元素原来是 `TaskMetadata`，经过 `toSummaryView` 后变成 `TaskSummaryView`。

`this::toSummaryView` 是方法引用，等价于：

```java
metadata -> this.toSummaryView(metadata)
```

所以这两种写法意思一样：

```java
.map(this::toSummaryView)
```

```java
.map(metadata -> this.toSummaryView(metadata))
```

```java
.toList()
```

把 Stream 流水线重新收集成 `List` 并返回。

## 等价的 for 循环写法

```java
public List<TaskSummaryView> listAll() {
    List<TaskSummaryView> views = new ArrayList<>();

    for (TaskMetadata metadata : taskStore.listAll()) {
        TaskSummaryView view = toSummaryView(metadata);
        views.add(view);
    }

    return views;
}
```

Stream 写法更短，for 循环写法更直观。刚开始学习时，可以先在脑子里把 Stream 翻译成 for 循环。

## listByStatus 的区别

```java
taskStore.listByStatus(status)
```

这一步已经先按状态过滤了任务。

后面的：

```java
.stream()
.map(this::toSummaryView)
.toList()
```

和 `listAll` 完全一样，都是把 `TaskMetadata` 转成 `TaskSummaryView`。

## 记忆方式

```text
stream()  开始流水线
map()     一个个转换
toList()  收集回 List
```

在这个项目里：

```text
TaskMetadata 是内部模型
TaskSummaryView 是对外展示模型
map(this::toSummaryView) 就是批量做模型转换
```

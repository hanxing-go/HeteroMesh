# Class 对象与 .class 语法

## 先忘掉泛型，看最朴素的东西

Java 里每个类型（String、int、TaskService、你自己写的任何类）在 JVM 里都有一个对应的「说明书」对象，类型是 `Class`。

```
你写的类                    JVM 里对应的 Class 对象
────────                   ──────────────────────
String                     Class 对象（里面写着：String 有哪些字段、哪些方法）
int                        Class 对象（里面写着：int 是 32 位有符号整数）
TaskService                Class 对象（里面写着：TaskService 有 processImage、getStatus 方法）
TaskServiceImpl            Class 对象（里面写着：TaskServiceImpl 继承了谁、实现了什么）
```

## `.class` 就是获取这个「说明书」的语法

```java
// 三种获取方式，结果完全一样：

// 方式 1：类名.class（编译时就知道类型）
Class c1 = String.class;         // 拿到 String 的说明书
Class c2 = int.class;            // 拿到 int 的说明书
Class c3 = TaskService.class;    // 拿到 TaskService 的说明书

// 方式 2：对象.getClass()（运行时获取）
String s = "hello";
Class c4 = s.getClass();         // 拿到 String 的说明书（和 c1 是同一个对象！）

TaskServiceImpl impl = new TaskServiceImpl();
Class c5 = impl.getClass();      // 拿到 TaskServiceImpl 的说明书

// 方式 3：Class.forName("全限定名")（根据字符串名字查找）
Class c6 = Class.forName("java.lang.String");  // 和 c1 是同一个对象！
```

**三种方式拿到的是同一个东西——JVM 里那个类型对应的 Class 对象。**

## Class 对象里有什么？

```java
Class<String> c = String.class;

// 可以问它任何关于 String 类型的问题：
c.getName();         // "java.lang.String"  ← 类型的全限定名
c.getMethods();      // [length(), charAt(), substring(), ...]  ← 所有方法
c.getFields();       // [CASE_INSENSITIVE_ORDER]  ← 所有字段
c.getConstructors(); // [String(), String(String), ...]  ← 所有构造器
```

这就是反射的基础——先拿到类型的说明书（Class 对象），然后从说明书里查出方法、字段、构造器，最后用说明书来操作对象。

## `Class<?>` 里的 `<?>` 是什么？

就是泛型而已，表示「我不知道具体是哪种 Class」。

```java
Class<?> c = String.class;    // <?> = 不确定类型，可以是任何一种 Class
                              // 等价于 Class c（但编译器不会警告）

// 如果你知道具体类型，可以写：
Class<String> c2 = String.class;   // 意思是「这个 Class 对象描述的是 String 类型」
Class<Integer> c3 = int.class;     // 「这个 Class 对象描述的是 int 类型」

// 但大多数反射场景下，你在写代码时并不知道类型（类型名来自 JSON 字符串），
// 所以用 Class<?>：
Class<?> c4 = Class.forName(typeName);  // typeName 来自网络，运行时才知道
                                         // 所以泛型只能是 <?>
```

## 为什么反射代码里到处都是 `Class<?>`？

```java
// RpcDispatcher 里：

// 这个 typeNames 是从网络传来的 JSON 里解析出来的
// ["java.lang.String", "int"]
// 你在写代码时根本不知道用户会传什么类型 → 只能用 Class<?>
Class<?>[] paramTypes = new Class<?>[typeNames.length];  // 「几种不确定类型的 Class」

// 这个 service.getClass() 返回什么类型？
// 可能是 TaskServiceImpl，可能是 UserServiceImpl……
// 运行时才知道 → Class<?>（不确定具体哪个类的说明书）
Class<?> clazz = service.getClass();

// 这个方法声明为 Class<?>，意思是「返回一个类型的说明书，但我不告诉你是哪种」
private Class<?> toClass(String typeName) {
    return switch (typeName) {
        case "int" -> int.class;                     // Class<Integer>
        case "long" -> long.class;                   // Class<Long>
        default -> Class.forName(typeName);          // Class<?>
    };
}
```

## 对标到 `method.invoke()`，为什么需要 Class

```java
// getMethod 需要参数类型的 Class 对象，才能区分重载方法：

// 接口：
void process(String path);        // 需要 [String.class] 才能找到
void process(byte[] data);        // 需要 [byte[].class] 才能找到

// 如果只有一个方法名 "process"，不知道参数类型，JVM 无法确定你要哪个方法
clazz.getMethod("process", String.class);    // → 找到 process(String)
clazz.getMethod("process", byte[].class);    // → 找到 process(byte[])
clazz.getMethod("process");                  // → 如果只有一个 process 可以，有重载就报错
```

## 一句话总结

| 概念 | 解释 |
|------|------|
| `类名.class` | 获取这个类型的「说明书」对象 |
| `对象.getClass()` | 获取这个对象所属类型的「说明书」 |
| `Class.forName("名字")` | 根据字符串名字查找「说明书」 |
| `Class<?>` | 一个不确定具体类型的「说明书」 |
| `<?>` | 泛型通配符，意思是「我不知道，运行时确定」 |

**Class 对象 = JVM 里存储的「类型的完整说明书」。反射就是把说明书拿出来，然后根据说明书去操作对象。**

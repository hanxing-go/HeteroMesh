## logback

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="com.heteromesh" level="DEBUG"/>
    <logger name="io.netty" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```



```
<appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
```

- **`<appender>`**：定义了一个日志的“输出目的地”。
- **`name="STDOUT"`**：给这个输出目的地起个名字叫 `STDOUT`（Standard Output 的缩写，代表标准输出）。
- **`class="...ConsoleAppender"`**：指定具体的实现类。`ConsoleAppender` 意思是**将日志打印到控制台（屏幕）上**。如果是输出到文件，这里会换成 `FileAppender` 相关的类。
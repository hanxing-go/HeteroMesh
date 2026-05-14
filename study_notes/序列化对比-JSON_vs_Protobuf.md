# 序列化对比：JSON vs Binary (Protobuf风格)

## 一、为什么 JSON 体积大？

JSON 序列化时会携带大量"结构开销"：

```json
{"type":1,"requestId":"abc-123","body":"你好，世界！"}
```

- 字段名每次都传输：`"type"` `"requestId"` `"body"` → 约 30 字节
- JSON 结构符号：`{` `}` `"` `:` `,` → 每条消息约 10 字节
- 数字用文本表示：`1` 只占 1 字节还行，但更大的数字就浪费

## 二、二进制怎么省空间？

Protobuf 风格的二进制编码用**字段编号**代替字段名：

| 字段 | JSON 传输 | 二进制传输 |
|------|----------|-----------|
| type | `"type":1` (7 bytes) | `0x01` (1 byte) |
| requestId | `"requestId":"abc"` (17 bytes) | `0x03 abc` (4 bytes, 前缀长度) |
| body | `"body":"你好"` (13 bytes) | `0x06 你好` (7 bytes, 前缀长度) |

另外数字用 **varint** 编码：小数字只占 1 字节，没有结构符号。

## 三、Varint 编码原理

数字 300 的例子：
- 二进制：100101100（9 位）
- 按 7 位一组拆分：`0000010` `0101100`
- 小端序输出：`0xAC` `0x02`（2 字节）

```java
// 编码：每字节低 7 位存数据，最高位=1 表示"还有下一字节"
while ((value & ~0x7F) != 0) {
    buf.put((byte) ((value & 0x7F) | 0x80)); // 未完
    value >>>= 7;
}
buf.put((byte) value); // 最后一字节，最高位=0
```

## 四、实测结果（100,000 次迭代）

| 指标 | JSON (Gson) | Binary (Codec) | 提升 |
|------|------------|----------------|------|
| 体积 | 126 bytes | 90 bytes | 节省 28.6% |
| 编码 | 1305 ns/op | 283 ns/op | 快 4.6x |
| 解码 | 894 ns/op | 153 ns/op | 快 5.8x |

## 五、选型建议

- **开发/调试**：用 JSON，人能读，方便 curl/postman 测试
- **生产环境**：用二进制（Protobuf），体积小、速度快
- **面试说：** JSON 和 Protobuf 都可以，我们做了双方案，通过开关切换。实际上线用 Protobuf 节省带宽。

## 六、关键代码位置

- JSON：`MessageSerializer.java` — Gson 序列化/反序列化
- 二进制：`MessageCodec.java` — varint + length-delimited 编码
- 对比测试：`ProtocolBenchmark.java` — 体积 + 编解码性能

package com.heteromesh.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Protobuf 风格的二进制序列化器
 *
 * 和 JSON（MessageSerializer）做对比：
 *   - JSON：字段名每次都传，{ "requestId":"abc", "payload":"你好" } → 40+ 字节
 *   - 二进制：字段用编号代替，字符串前缀长度 → ~25 字节
 *
 * 编码格式（简化版 Protobuf wire format）：
 *   Field 0 (type):   varint — 消息类型编号
 *   Field 1 (reqId):  length-delimited — 长度(varint) + UTF-8 字节
 *   Field 2 (body):   length-delimited — 长度(varint) + UTF-8 字节
 *
 * 为什么比 JSON 小？
 *   1. 字段名不传输（用编号 0/1/2 代替 "type"/"requestId"/"body"）
 *   2. 数字用 varint 编码：小数字只占 1 字节（JSON 里 "1" 也是 1 字节但带引号）
 *   3. 没有 { } " : , 这些 JSON 的结构符号
 */
public class MessageCodec {

    /**
     * 编码：Message → 字节数组
     */
    public static byte[] encode(Message message) {
        // 预估大小：type(1) + reqIdLen(1) + reqId(~36) + bodyLen(1) + body(~100)
        int estimated = 1 + 1 + 36 + 1 + 100;
        ByteBuffer buf = ByteBuffer.allocate(estimated * 2); // 留余量

        // Field 0: type → varint
        writeVarint(buf, message.getType().getCode());

        // Field 1: requestId → length-delimited
        byte[] reqIdBytes = message.getRequestId().getBytes(StandardCharsets.UTF_8);
        writeVarint(buf, reqIdBytes.length);
        buf.put(reqIdBytes);

        // Field 2: body → length-delimited
        byte[] bodyBytes = message.getBody().getBytes(StandardCharsets.UTF_8);
        writeVarint(buf, bodyBytes.length);
        buf.put(bodyBytes);

        // 截取实际使用的部分
        byte[] result = new byte[buf.position()];
        buf.flip();
        buf.get(result);
        return result;
    }

    /**
     * 解码：字节数组 → Message
     */
    public static Message decode(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);

        // Field 0: type
        byte typeCode = (byte) readVarint(buf);
        MessageType type = MessageType.fromCode(typeCode);

        // Field 1: requestId
        int reqIdLen = readVarint(buf);
        byte[] reqIdBytes = new byte[reqIdLen];
        buf.get(reqIdBytes);
        String requestId = new String(reqIdBytes, StandardCharsets.UTF_8);

        // Field 2: body
        int bodyLen = readVarint(buf);
        byte[] bodyBytes = new byte[bodyLen];
        buf.get(bodyBytes);
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        return new Message(type, requestId, body);
    }

    // ========== Varint 编码 ==========

    /**
     * 写入 varint：每字节低 7 位存数据，最高位表示「还有下一字节」
     * 例如：数字 300
     *   二进制：100101100（9 位）
     *   拆成 7+2 位：1010 1100 0000 0010
     *   小端序输出：0xAC 0x02
     */
    private static void writeVarint(ByteBuffer buf, int value) {
        while ((value & ~0x7F) != 0) {      // 还有超过 7 位的部分？
            buf.put((byte) ((value & 0x7F) | 0x80)); // 低 7 位 | 最高位=1（未完）
            value >>>= 7;                    // 右移 7 位，处理下一组
        }
        buf.put((byte) value);               // 最后一组，最高位=0（结束）
    }

    private static int readVarint(ByteBuffer buf) {
        int result = 0;
        int shift = 0;
        while (true) {
            byte b = buf.get();
            result |= (b & 0x7F) << shift;   // 取低 7 位，放到正确位置
            if ((b & 0x80) == 0) break;      // 最高位=0 → 结束了
            shift += 7;
        }
        return result;
    }
}

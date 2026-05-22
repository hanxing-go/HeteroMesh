package com.heteromesh.serializer;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class BinarySerializer implements Serializer{

    @Override
    public byte[] serialize(Message message) {
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

    @Override
    public Message deserialize(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);

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

    @Override
    public String name() {
        return "binary";
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

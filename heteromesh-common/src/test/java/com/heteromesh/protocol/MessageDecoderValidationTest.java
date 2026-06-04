package com.heteromesh.protocol;

import com.google.gson.JsonObject;
import com.heteromesh.serializer.SerializerCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageDecoder 的协议防御测试。
 *
 * 这些测试不启动真实 Netty Server，而是用 EmbeddedChannel 模拟 pipeline。
 * 好处是可以手动构造 ByteBuf，精确控制协议头中的 magic/version/type/length，
 * 从而验证非法网络帧会在 Decoder 阶段被拒绝，不会进入业务 Handler。
 */
class MessageDecoderValidationTest {

    @Test
    void shouldDecodeValidMessage() {
        /*
         * 正常路径测试：
         * 先让 MessageEncoder 按真实协议编码，再让 MessageDecoder 解码。
         * 这个测试保证新增的校验逻辑不会破坏合法消息的往返。
         */
        Message original = Message.createTaskRequest("valid-payload");
        EmbeddedChannel channel = new EmbeddedChannel(new MessageEncoder(), new MessageDecoder());

        assertTrue(channel.writeOutbound(original));
        ByteBuf encoded = channel.readOutbound();

        assertTrue(channel.writeInbound(encoded));
        Message decoded = channel.readInbound();

        assertEquals(original.getType(), decoded.getType());
        assertEquals(original.getRequestId(), decoded.getRequestId());
        assertEquals(original.getBody(), decoded.getBody());
        assertFalse(channel.finish());
    }

    @Test
    void shouldRejectInvalidMagicNumber() {
        /*
         * magic 用来判断“这是不是 HeteroMesh 协议”。
         * 这里故意写 0xDEADBEEF，虽然后面的字段都合法，也应该在 Decoder 阶段被拒绝。
         */
        ByteBuf frame = buildFrame(
                0xDEADBEEF,
                MessageEncoder.VERSION,
                SerializerCode.JSON.getCode(),
                MessageType.PING.getCode(),
                jsonBody(MessageType.PING)
        );

        assertDecoderRejects(frame, CorruptedFrameException.class);
    }

    @Test
    void shouldRejectUnsupportedVersion() {
        /*
         * version 用来做协议演进。
         * 当前只支持 0x01，这里写 0x02，模拟旧/新客户端协议不兼容。
         */
        ByteBuf frame = buildFrame(
                MessageEncoder.MAGIC_NUMBER,
                (byte) 0x02,
                SerializerCode.JSON.getCode(),
                MessageType.PING.getCode(),
                jsonBody(MessageType.PING)
        );

        assertDecoderRejects(frame, CorruptedFrameException.class);
    }

    @Test
    void shouldRejectUnknownMessageType() {
        /*
         * 协议头 type 是框架层在反序列化前能看到的消息类型。
         * 这里写一个 MessageType 中不存在的 code，验证非法类型不会进入业务层。
         */
        ByteBuf frame = buildFrame(
                MessageEncoder.MAGIC_NUMBER,
                MessageEncoder.VERSION,
                SerializerCode.JSON.getCode(),
                (byte) 0x7F,
                jsonBody(MessageType.PING)
        );

        assertDecoderRejects(frame, IllegalArgumentException.class);
    }

    @Test
    void shouldRejectHeaderAndBodyTypeMismatch() {
        /*
         * 协议头 type 和 body 里的 Message.type 是两份信息。
         * 正常情况下它们必须一致。
         *
         * 这里让 header 声称自己是 PING，但 body 反序列化后是 TASK_REQUEST，
         * 用来验证 Decoder 会拒绝这种不一致的帧。
         */
        ByteBuf frame = buildFrame(
                MessageEncoder.MAGIC_NUMBER,
                MessageEncoder.VERSION,
                SerializerCode.JSON.getCode(),
                MessageType.PING.getCode(),
                jsonBody(MessageType.TASK_REQUEST)
        );

        assertDecoderRejects(frame, CorruptedFrameException.class);
    }

    private void assertDecoderRejects(ByteBuf frame, Class<? extends Throwable> expectedType) {
        EmbeddedChannel channel = new EmbeddedChannel(new MessageDecoder());

        Throwable thrown = assertThrows(Throwable.class, () -> channel.writeInbound(frame));
        /*
         * Netty 可能会把 Decoder 内部抛出的异常包装成 DecoderException。
         * 所以这里既检查异常本身，也检查 cause，避免测试和 Netty 包装细节绑死。
         */
        boolean matched = expectedType.isInstance(thrown)
                || (thrown.getCause() != null && expectedType.isInstance(thrown.getCause()));
        assertTrue(matched,
                "expected " + expectedType.getSimpleName() + " but got " + thrown.getClass().getSimpleName());
    }

    private ByteBuf buildFrame(int magic, byte version, byte serializerCode, byte typeCode, byte[] body) {
        ByteBuf frame = Unpooled.buffer(MessageEncoder.HEADER_LENGTH + body.length);
        frame.writeInt(magic);
        frame.writeByte(version);
        frame.writeByte(serializerCode);
        frame.writeByte(typeCode);
        frame.writeInt(body.length);
        frame.writeBytes(body);
        return frame;
    }

    private byte[] jsonBody(MessageType bodyType) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", bodyType.getCode());
        envelope.addProperty("requestId", "test-request");
        envelope.addProperty("payload", "test-payload");
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }
}

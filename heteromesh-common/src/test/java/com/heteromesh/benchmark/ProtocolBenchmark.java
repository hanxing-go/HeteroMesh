package com.heteromesh.benchmark;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageCodec;
import com.heteromesh.protocol.MessageSerializer;

import java.nio.ByteBuffer;

/**
 * 序列化性能对比：JSON (Gson) vs Protobuf 风格二进制编码
 *
 * 直接运行 main() 方法
 */
public class ProtocolBenchmark {

    private static final int WARMUP = 10_000;     // 预热次数
    private static final int ITERATIONS = 100_000; // 实测次数

    public static void main(String[] args) {
        Message msg = Message.createTaskRequest(
                "请用一句话解释什么是 Java 虚拟线程？");

        // ========== 1. 体积对比 ==========
        System.out.println("========== 序列化体积对比 ==========");

        ByteBuffer jsonBuf = MessageSerializer.encode(msg);
        byte[] jsonBytes = new byte[jsonBuf.remaining()];
        jsonBuf.get(jsonBytes);

        byte[] binaryBytes = MessageCodec.encode(msg);

        System.out.printf("JSON  (Gson):   %d bytes\n", jsonBytes.length);
        System.out.printf("Binary (Codec): %d bytes\n", binaryBytes.length);
        System.out.printf("节省: %d bytes (%.1f%%)\n\n",
                jsonBytes.length - binaryBytes.length,
                (1 - (double) binaryBytes.length / jsonBytes.length) * 100);

        // ========== 2. 编码性能对比 ==========
        System.out.println("========== 编码性能对比（预热 " + WARMUP + " + 实测 " + ITERATIONS + " 次）==========");

        // JSON 编码
        for (int i = 0; i < WARMUP; i++) MessageSerializer.encode(msg);
        long jsonStart = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) MessageSerializer.encode(msg);
        long jsonEncodeTime = System.nanoTime() - jsonStart;

        // Binary 编码
        for (int i = 0; i < WARMUP; i++) MessageCodec.encode(msg);
        long binStart = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) MessageCodec.encode(msg);
        long binEncodeTime = System.nanoTime() - binStart;

        double jsonEncodeUs = jsonEncodeTime / 1000.0 / ITERATIONS;
        double binEncodeUs = binEncodeTime / 1000.0 / ITERATIONS;

        System.out.printf("JSON  encode:   %.0f ns/op  (%d ms / %d ops)\n",
                jsonEncodeUs * 1000, jsonEncodeTime / 1_000_000, ITERATIONS);
        System.out.printf("Binary encode:  %.0f ns/op  (%d ms / %d ops)\n",
                binEncodeUs * 1000, binEncodeTime / 1_000_000, ITERATIONS);
        System.out.printf("Binary 快 %.1fx\n\n", jsonEncodeUs / binEncodeUs);

        // ========== 3. 解码性能对比 ==========
        System.out.println("========== 解码性能对比（预热 " + WARMUP + " + 实测 " + ITERATIONS + " 次）==========");

        byte[] binData = MessageCodec.encode(msg);
        ByteBuffer jsonData = MessageSerializer.encode(msg);

        // JSON 解码
        for (int i = 0; i < WARMUP; i++) {
            jsonData.rewind();
            MessageSerializer.decode(jsonData);
        }
        jsonData.rewind();
        long jsonDecStart = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            jsonData.rewind();
            MessageSerializer.decode(jsonData);
        }
        long jsonDecTime = System.nanoTime() - jsonDecStart;

        // Binary 解码
        for (int i = 0; i < WARMUP; i++) MessageCodec.decode(binData);
        long binDecStart = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) MessageCodec.decode(binData);
        long binDecTime = System.nanoTime() - binDecStart;

        double jsonDecUs = jsonDecTime / 1000.0 / ITERATIONS;
        double binDecUs = binDecTime / 1000.0 / ITERATIONS;

        System.out.printf("JSON  decode:   %.0f ns/op  (%d ms / %d ops)\n",
                jsonDecUs * 1000, jsonDecTime / 1_000_000, ITERATIONS);
        System.out.printf("Binary decode:  %.0f ns/op  (%d ms / %d ops)\n",
                binDecUs * 1000, binDecTime / 1_000_000, ITERATIONS);
        System.out.printf("Binary 快 %.1fx\n\n", jsonDecUs / binDecUs);

        // ========== 4. 总结 ==========
        System.out.println("========== 总结 ==========");
        System.out.println("体积: Binary 只有 JSON 的 " +
                String.format("%.1f%%", (double) binaryBytes.length / jsonBytes.length * 100));
        System.out.println("编码速度: Binary 是 JSON 的 " +
                String.format("%.1fx", jsonEncodeUs / binEncodeUs));
        System.out.println("解码速度: Binary 是 JSON 的 " +
                String.format("%.1fx", jsonDecUs / binDecUs));
        System.out.println();
        System.out.println("结论: 生产环境用二进制（Protobuf），开发调试用 JSON。");
    }
}

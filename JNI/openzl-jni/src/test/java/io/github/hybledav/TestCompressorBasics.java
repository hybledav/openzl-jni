package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TestCompressorBasics {

    @Test
    void roundTripSmallPayload() {
        byte[] payload = "hello-openzl-jni".getBytes(StandardCharsets.UTF_8);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed, "Compression should succeed for small payloads");

            byte[] restored = compressor.decompress(compressed);
            assertNotNull(restored, "Decompression should succeed for small payloads");
            assertArrayEquals(payload, restored);
        }
    }

    @Test
    void roundTripHighlyCompressiblePayload() {
        byte[] payload = new byte[128 * 1024];
        for (int i = 0; i < payload.length; ++i) {
            payload[i] = (byte) (i % 7);
        }

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed, "Compression should produce output");
            assertTrue(compressed.length < payload.length / 10, "Payload should compress well");

            byte[] restored = compressor.decompress(compressed);
            assertNotNull(restored);
            assertArrayEquals(payload, restored);
        }
    }

    @Test
    void compressorCanBeReusedForMultiplePayloads() {
        byte[] first = "first payload".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second payload is a bit longer to exercise the compressor reuse"
                .getBytes(StandardCharsets.UTF_8);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertArrayEquals(first, decompressRoundTrip(compressor, first));
            assertArrayEquals(second, decompressRoundTrip(compressor, second));
        }
    }

    @Test
    void decompressingGarbageReturnsNull() {
        byte[] garbage = new byte[2048];
        new Random(0xdeadbeef).nextBytes(garbage);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertNull(compressor.decompress(garbage), "Garbage payload should fail to decompress");
        }
    }

    private static byte[] decompressRoundTrip(OpenZLCompressor compressor, byte[] input) {
        byte[] compressed = compressor.compress(input);
        assertNotNull(compressed, "Compression returned null");
        byte[] restored = compressor.decompress(compressed);
        assertNotNull(restored, "Decompression returned null");
        return restored;
    }
}

package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TestCompressorEdgeCases {

    @Test
    void compressingNullInputThrows() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(NullPointerException.class, () -> compressor.compress(null));
        }
    }

    @Test
    void decompressingNullInputThrows() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(NullPointerException.class, () -> compressor.decompress(null));
        }
    }

    @Test
    void truncatedPayloadFailsGracefully() {
        byte[] payload = "truncation-test".getBytes(StandardCharsets.UTF_8);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed);

            byte[] truncated = Arrays.copyOf(compressed, Math.max(1, compressed.length / 2));
            assertNull(
                    compressor.decompress(truncated),
                    "Truncated payload should return null instead of throwing");
        }
    }

    @Test
    void operationsAfterCloseReturnDefaults() throws Exception {
        OpenZLCompressor compressor = new OpenZLCompressor();
        compressor.close();

        assertNull(
                compressor.compress("data".getBytes(StandardCharsets.UTF_8)),
                "compress() should return null after close()");
        assertNull(
                compressor.decompress(new byte[] {1, 2, 3}),
                "decompress() should return null after close()");
        assertEquals(0, compressor.getParameter(0), "getParameter should return default after close()");
        assertEquals("", compressor.serialize(), "serialize should return empty string after close()");
        assertEquals("", compressor.serializeToJson(), "serializeToJson should return empty string after close()");
        assertDoesNotThrow(compressor::close, "close() may be called multiple times");
    }

    @Test
    void serializeMethodsAlwaysReturnStrings() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            String data = compressor.serialize();
            String json = compressor.serializeToJson();
            assertNotNull(data, "serialize() should not return null");
            assertNotNull(json, "serializeToJson() should not return null");
        }
    }

    @Test
    void randomGarbageInputFailsToDecompress() {
        byte[] garbage = new byte[4096];
        new Random(0xdeadbeef).nextBytes(garbage);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertNull(compressor.decompress(garbage), "Garbage payload should fail to decompress");
        }
    }
}

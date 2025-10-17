package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TestCompressorOffsets {

    @Test
    void compressAndDecompressWithOffsets() {
        byte[] payload = "offset-behaviour-check".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] outputBuffer = new byte[(int) OpenZLCompressor.maxCompressedSize(payload.length) + 16];
            Arrays.fill(outputBuffer, (byte) 0x7F);
            int offset = 8;
            int written = compressor.compress(payload, 0, payload.length, outputBuffer, offset, outputBuffer.length - offset);
            assertTrue(written > 0);

            for (int i = 0; i < offset; ++i) {
                assertEquals((byte) 0x7F, outputBuffer[i], "prefix bytes should remain untouched");
            }

            byte[] decompressionBuffer = new byte[payload.length + offset + 5];
            Arrays.fill(decompressionBuffer, (byte) 0x13);
            int decompressed = compressor.decompress(outputBuffer, offset, written, decompressionBuffer, offset, payload.length);
            assertEquals(payload.length, decompressed);
            byte[] copy = Arrays.copyOfRange(decompressionBuffer, offset, offset + payload.length);
            assertArrayEquals(payload, copy);

            for (int i = 0; i < offset; ++i) {
                assertEquals((byte) 0x13, decompressionBuffer[i], "destination prefix should remain untouched");
            }
        }
    }
}

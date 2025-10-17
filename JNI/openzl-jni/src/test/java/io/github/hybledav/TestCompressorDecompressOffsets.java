package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TestCompressorDecompressOffsets {

    @Test
    void decompressIntoOffsetBuffer() {
        byte[] payload = "offset-roundtrip".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed);

            byte[] target = new byte[payload.length + 10];
            Arrays.fill(target, (byte) 0x55);
            int offset = 5;
            int written = compressor.decompress(compressed, 0, compressed.length, target, offset, payload.length);
            assertEquals(payload.length, written);
            assertArrayEquals(payload, Arrays.copyOfRange(target, offset, offset + payload.length));
            for (int i = 0; i < offset; ++i) {
                assertEquals((byte) 0x55, target[i]);
            }
        }
    }
}

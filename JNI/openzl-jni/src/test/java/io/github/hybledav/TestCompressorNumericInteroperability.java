package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TestCompressorNumericInteroperability {

    @Test
    void numericStreamsDecompressAcrossIndependentInstances() {
        float[] payload = new float[1024];
        Random random = new Random(1234);
        for (int i = 0; i < payload.length; ++i) {
            payload[i] = random.nextFloat();
        }

        byte[] compressed;
        try (OpenZLCompressor writer = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            compressed = writer.compressFloats(payload);
            assertNotNull(compressed);
        }

        try (OpenZLCompressor reader = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            float[] restored = reader.decompressFloats(compressed);
            assertArrayEquals(payload, restored, 0.0f);
        }
    }
}

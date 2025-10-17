package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TestCompressorNegativeZero {

    @Test
    void compressFloatsPreservesNegativeZero() {
        float[] values = new float[] {0.0f, -0.0f, 1.0f};
        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] compressed = compressor.compressFloats(values);
            assertNotNull(compressed);
            float[] restored = compressor.decompressFloats(compressed);
            assertEquals(values.length, restored.length);
            assertEquals(Float.floatToIntBits(values[1]), Float.floatToIntBits(restored[1]));
        }
    }

    @Test
    void compressDoublesPreservesNegativeZero() {
        double[] values = new double[] {0.0d, -0.0d, 1.0d};
        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] compressed = compressor.compressDoubles(values);
            assertNotNull(compressed);
            double[] restored = compressor.decompressDoubles(compressed);
            assertEquals(values.length, restored.length);
            assertEquals(Double.doubleToLongBits(values[1]), Double.doubleToLongBits(restored[1]));
        }
    }
}

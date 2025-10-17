package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TestCompressorNaNPayloads {

    @Test
    void compressFloatsPreservesNaNPayloadBits() {
        float specialNaN = Float.intBitsToFloat(0x7FC12345);
        float[] values = new float[] {1.0f, specialNaN, -2.5f};

        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] compressed = compressor.compressFloats(values);
            assertNotNull(compressed);
            float[] restored = compressor.decompressFloats(compressed);
            assertEquals(values.length, restored.length, "Length should match after round-trip");
            for (int i = 0; i < values.length; ++i) {
                int expectedBits = Float.floatToIntBits(values[i]);
                int actualBits = Float.floatToIntBits(restored[i]);
                assertEquals(expectedBits, actualBits, "Float bits differ at index " + i);
            }
        }
    }

    @Test
    void compressDoublesPreservesNaNPayloadBits() {
        double specialNaN = Double.longBitsToDouble(0x7FF8123412345678L);
        double[] values = new double[] {42.0d, specialNaN, -13.5d};

        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] compressed = compressor.compressDoubles(values);
            assertNotNull(compressed);
            double[] restored = compressor.decompressDoubles(compressed);
            assertEquals(values.length, restored.length, "Length should match after round-trip");
            for (int i = 0; i < values.length; ++i) {
                long expectedBits = Double.doubleToLongBits(values[i]);
                long actualBits = Double.doubleToLongBits(restored[i]);
                assertEquals(expectedBits, actualBits, "Double bits differ at index " + i);
            }
        }
    }
}

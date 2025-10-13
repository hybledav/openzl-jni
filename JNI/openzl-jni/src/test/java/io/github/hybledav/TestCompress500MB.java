package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TestCompress500MB {
    private static final int BYTES_PER_MB = 1024 * 1024;
    private static final int TEST_SIZE_BYTES = 500 * BYTES_PER_MB;
    private static final int PALETTE_BLOCKS = 16;
    private static final int BLOCK_SIZE = 64 * 1024;
    private static final long PSEUDO_RANDOM_SEED = 0x5eed1234L;

    private static byte[] buildCompressibleData() {
        byte[] data = new byte[TEST_SIZE_BYTES];
        byte[][] palette = new byte[PALETTE_BLOCKS][BLOCK_SIZE];
        Random random = new Random(PSEUDO_RANDOM_SEED);

        for (byte[] block : palette) {
            random.nextBytes(block);
        }

        for (int offset = 0; offset < TEST_SIZE_BYTES; offset += BLOCK_SIZE) {
            byte[] block = palette[random.nextInt(PALETTE_BLOCKS)];
            int chunk = Math.min(BLOCK_SIZE, TEST_SIZE_BYTES - offset);
            System.arraycopy(block, 0, data, offset, chunk);
        }

        return data;
    }

    @Test
    void roundTripMaintainsDataIntegrity() {
        byte[] source = buildCompressibleData();
        byte[] compressed;
        byte[] restored;

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressed = compressor.compress(source);
            assertNotNull(compressed, "Compression should produce an output buffer");

            restored = compressor.decompress(compressed);
        }

        assertNotNull(restored, "Decompression should return data");
        assertEquals(source.length, restored.length, "Decompressed size should match the original");
        assertArrayEquals(source, restored, "Decompressed content should match the original payload");
    }
}

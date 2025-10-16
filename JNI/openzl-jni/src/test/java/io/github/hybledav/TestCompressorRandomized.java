package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TestCompressorRandomized {
    private static final OpenZLGraph[] TEST_GRAPHS = {
        OpenZLGraph.AUTO,
        OpenZLGraph.ZSTD,
        OpenZLGraph.GENERIC
    };

    @Test
    void randomPayloadsAcrossMultipleGraphsRoundTrip() {
        Random random = new Random(0x5eedf00dL);

        for (OpenZLGraph graph : TEST_GRAPHS) {
            try (OpenZLCompressor compressor = new OpenZLCompressor(graph)) {
                for (int iteration = 0; iteration < 12; ++iteration) {
                    int length = random.nextInt(48 * 1024);
                    byte[] payload = new byte[length];
                    random.nextBytes(payload);
                    if (length > 0 && iteration % 3 == 0) {
                        // Inject a repeating pattern to exercise compression heuristics.
                        for (int i = 0; i < length; i += 8) {
                            payload[i] = (byte) (iteration & 0xFF);
                        }
                    }

                    long bound = Math.max(1L, OpenZLCompressor.maxCompressedSize(length));
                    byte[] staging = new byte[(int) bound];
                    int written = compressor.compress(payload, 0, payload.length, staging, 0, staging.length);
                    assertTrue(written >= 0);
                    byte[] compressed = Arrays.copyOf(staging, written);

                    byte[] restoredBuffer = new byte[Math.max(1, length)];
                    int restored = compressor.decompress(compressed, 0, compressed.length, restoredBuffer, 0, restoredBuffer.length);
                    assertEquals(length, restored);
                    assertArrayEquals(payload, Arrays.copyOf(restoredBuffer, length));

                    if (compressed.length > 0) {
                        assertEquals(length, compressor.getDecompressedSize(compressed));
                        ByteBuffer sizeBuffer = ByteBuffer.allocateDirect(compressed.length);
                        sizeBuffer.put(compressed).flip();
                        assertEquals(length, compressor.getDecompressedSize(sizeBuffer));
                    }

                    ByteBuffer src = ByteBuffer.allocateDirect(Math.max(1, length));
                    src.put(payload);
                    src.flip();

                    ByteBuffer dst = ByteBuffer.allocateDirect((int) bound);
                    int bufferWritten = compressor.compress(src, dst);
                    assertEquals(dst.position(), bufferWritten);
                    dst.flip();
                    byte[] directCompressed = new byte[dst.remaining()];
                    dst.get(directCompressed);
                    assertEquals(compressed.length, directCompressed.length);
                    assertArrayEquals(compressed, directCompressed);

                    if (directCompressed.length > 0) {
                        ByteBuffer compressedDirect = ByteBuffer.allocateDirect(directCompressed.length);
                        compressedDirect.put(directCompressed).flip();
                        ByteBuffer restoredDirect = ByteBuffer.allocateDirect(Math.max(1, length));
                        int restoredDirectBytes = compressor.decompress(compressedDirect, restoredDirect);
                        assertEquals(length, restoredDirectBytes);
                        restoredDirect.flip();
                        byte[] finalBytes = new byte[length];
                        restoredDirect.get(finalBytes);
                        assertArrayEquals(payload, finalBytes);

                        OpenZLCompressionInfo info = compressor.inspect(directCompressed);
                        assertEquals(length, info.originalSize());
                        assertEquals(directCompressed.length, info.compressedSize());
                        assertTrue(info.compressionRatio() >= 0.0d);
                        assertNotNull(info.flavor());
                    }

                    compressor.reset();
                }
            }
        }
    }
}

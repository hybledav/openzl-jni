package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TestCompressorAdvanced {

    @Test
    void numericRoundTripAndMetadata() {
        int[] ints = new int[1024];
        Random rnd = new Random(42);
        for (int i = 0; i < ints.length; ++i) {
            ints[i] = rnd.nextInt();
        }

        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] compressed = compressor.compressInts(ints);
            assertNotNull(compressed);

            int[] restored = compressor.decompressInts(compressed);
            assertArrayEquals(ints, restored, "int arrays should round-trip");

            OpenZLCompressionInfo info = compressor.inspect(compressed);
            assertEquals(OpenZLCompressionInfo.DataFlavor.NUMERIC, info.flavor());
            assertEquals(OpenZLGraph.NUMERIC, info.graph());
            OptionalLong count = info.elementCount();
            count.ifPresent(value -> assertEquals(ints.length, value));
            assertEquals(ints.length * (long) Integer.BYTES, info.originalSize());
            assertTrue(info.compressedSize() > 0);
        }
    }

    @Test
    void metadataFromDirectBufferMatchesArrayPath() {
        byte[] payload = new byte[4096];
        new Random(13).nextBytes(payload);

        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.ZSTD)) {
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed);

            OpenZLCompressionInfo infoArray = compressor.inspect(compressed);

            ByteBuffer direct = ByteBuffer.allocateDirect(compressed.length).order(ByteOrder.nativeOrder());
            direct.put(compressed).flip();
            OpenZLCompressionInfo infoBuffer = compressor.inspect(direct);

            assertEquals(infoArray.originalSize(), infoBuffer.originalSize());
            assertEquals(infoArray.compressedSize(), infoBuffer.compressedSize());
            assertEquals(infoArray.flavor(), infoBuffer.flavor());
        }
    }
}

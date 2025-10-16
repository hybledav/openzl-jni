package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TestCompressorNumericVariants {

    @Test
    void numericPrimitiveSupportCoversAllTypes() {
        Random random = new Random(0x1cedbabeL);

        int[] ints = new int[2048];
        for (int i = 0; i < ints.length; ++i) {
            ints[i] = random.nextInt();
        }

        long[] longs = new long[1024];
        for (int i = 0; i < longs.length; ++i) {
            longs[i] = random.nextLong();
        }

        float[] floats = new float[1536];
        for (int i = 0; i < floats.length; ++i) {
            floats[i] = random.nextFloat() * 1_000.0f - 500.0f;
        }

        double[] doubles = new double[768];
        for (int i = 0; i < doubles.length; ++i) {
            doubles[i] = random.nextDouble() * 10_000.0d - 5_000.0d;
        }

        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] compressedInts = compressor.compressInts(ints);
            assertNotNull(compressedInts);
            assertArrayEquals(ints, compressor.decompressInts(compressedInts));
            OpenZLCompressionInfo infoInts = compressor.inspect(compressedInts);
            assertEquals(OpenZLGraph.NUMERIC, infoInts.graph());
            assertEquals(OpenZLCompressionInfo.DataFlavor.NUMERIC, infoInts.flavor());
            infoInts.elementCount().ifPresent(count -> assertEquals(ints.length, count));
            assertEquals((long) ints.length * Integer.BYTES, infoInts.originalSize());
            long compressedIntsSize = infoInts.compressedSize();
            if (compressedIntsSize < -1) {
                fail("Unexpected compressed size for ints: " + compressedIntsSize);
            }
            if (compressedIntsSize >= 0 && infoInts.originalSize() > 0) {
                assertTrue(infoInts.compressionRatio() >= 0.0d);
            }
            assertEquals(infoInts.originalSize(), compressor.getDecompressedSize(compressedInts));
            ByteBuffer directInt = ByteBuffer.allocateDirect(compressedInts.length);
            directInt.put(compressedInts).flip();
            assertEquals(infoInts.originalSize(), compressor.getDecompressedSize(directInt));

            compressor.reset();

            byte[] compressedLongs = compressor.compressLongs(longs);
            assertNotNull(compressedLongs);
            assertArrayEquals(longs, compressor.decompressLongs(compressedLongs));
            OpenZLCompressionInfo infoLongs = compressor.inspect(compressedLongs);
            assertEquals(OpenZLCompressionInfo.DataFlavor.NUMERIC, infoLongs.flavor());
            assertEquals((long) longs.length * Long.BYTES, infoLongs.originalSize());
            infoLongs.elementCount().ifPresent(count -> assertEquals(longs.length, count));
            long compressedLongsSize = infoLongs.compressedSize();
            if (compressedLongsSize < -1) {
                fail("Unexpected compressed size for longs: " + compressedLongsSize);
            }
            if (compressedLongsSize >= 0 && infoLongs.originalSize() > 0) {
                assertTrue(infoLongs.compressionRatio() >= 0.0d);
            }
            assertEquals(infoLongs.originalSize(), compressor.getDecompressedSize(compressedLongs));

            compressor.reset();

            byte[] compressedFloats = compressor.compressFloats(floats);
            assertNotNull(compressedFloats);
            assertArrayEquals(floats, compressor.decompressFloats(compressedFloats), 0.0f);
            OpenZLCompressionInfo infoFloats = compressor.inspect(compressedFloats);
            assertEquals(OpenZLCompressionInfo.DataFlavor.NUMERIC, infoFloats.flavor());
            assertEquals((long) floats.length * Float.BYTES, infoFloats.originalSize());
            infoFloats.elementCount().ifPresent(count -> assertEquals(floats.length, count));
            long compressedFloatsSize = infoFloats.compressedSize();
            if (compressedFloatsSize < -1) {
                fail("Unexpected compressed size for floats: " + compressedFloatsSize);
            }
            if (compressedFloatsSize >= 0 && infoFloats.originalSize() > 0) {
                assertTrue(infoFloats.compressionRatio() >= 0.0d);
            }

            compressor.reset();

            byte[] compressedDoubles = compressor.compressDoubles(doubles);
            assertNotNull(compressedDoubles);
            assertArrayEquals(doubles, compressor.decompressDoubles(compressedDoubles), 0.0d);
            OpenZLCompressionInfo infoDoubles = compressor.inspect(compressedDoubles);
            assertEquals(OpenZLCompressionInfo.DataFlavor.NUMERIC, infoDoubles.flavor());
            assertEquals((long) doubles.length * Double.BYTES, infoDoubles.originalSize());
            infoDoubles.elementCount().ifPresent(count -> assertEquals(doubles.length, count));
            long compressedDoublesSize = infoDoubles.compressedSize();
            if (compressedDoublesSize < -1) {
                fail("Unexpected compressed size for doubles: " + compressedDoublesSize);
            }
            if (compressedDoublesSize >= 0 && infoDoubles.originalSize() > 0) {
                assertTrue(infoDoubles.compressionRatio() >= 0.0d);
            }
            assertEquals(infoDoubles.originalSize(), compressor.getDecompressedSize(compressedDoubles));
        }
    }
}

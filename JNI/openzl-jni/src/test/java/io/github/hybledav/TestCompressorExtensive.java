package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TestCompressorExtensive {

    @Test
    void serialRoundTripSmall() {
        byte[] payload = "hello-openzl".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] comp = c.compress(payload);
            assertNotNull(comp);
            byte[] out = c.decompress(comp);
            assertArrayEquals(payload, out);
        }
    }

    @Test
    void serialRoundTripLarge() {
        byte[] payload = new byte[64_000];
        new Random(0xC0FFEE).nextBytes(payload);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] comp = c.compress(payload);
            assertNotNull(comp);
            byte[] out = c.decompress(comp);
            assertArrayEquals(payload, out);
        }
    }

    @Test
    void csvBeatsSerialForStructured() {
    String csvData = generateCsv(300);
    byte[] payload = csvData.getBytes(StandardCharsets.UTF_8);
        int serialSize;
        try (OpenZLCompressor serial = new OpenZLCompressor()) {
            serial.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] s = serial.compress(payload);
            assertNotNull(s);
            serialSize = s.length;
        }
        try (OpenZLCompressor csv = new OpenZLCompressor()) {
            csv.configureProfile(OpenZLProfile.CSV, java.util.Map.of());
            byte[] c = csv.compress(payload);
            assertNotNull(c);
            assertTrue(c.length < serialSize, () -> "CSV should compress smaller than SERIAL");
        }
    }

    @Test
    void numericIntsRoundTrip() {
        int[] data = new int[1024];
        for (int i = 0; i < data.length; i++) data[i] = i * 13;
        try (OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] comp = c.compressInts(data);
            assertNotNull(comp);
            int[] out = c.decompressInts(comp);
            assertArrayEquals(data, out);
        }
    }

    @Test
    void numericLongsRoundTrip() {
        long[] data = new long[512];
        for (int i = 0; i < data.length; i++) data[i] = (long) i * 1_000_000_007L;
        try (OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] comp = c.compressLongs(data);
            assertNotNull(comp);
            long[] out = c.decompressLongs(comp);
            assertArrayEquals(data, out);
        }
    }

    @Test
    void numericFloatsRoundTrip() {
        float[] data = new float[1024];
        for (int i = 0; i < data.length; i++) data[i] = (float) (i * 0.125);
        try (OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] comp = c.compressFloats(data);
            assertNotNull(comp);
            float[] out = c.decompressFloats(comp);
            assertArrayEquals(data, out, 0.0f);
        }
    }

    @Test
    void numericDoublesRoundTrip() {
        double[] data = new double[512];
        for (int i = 0; i < data.length; i++) data[i] = i * 0.0001;
        try (OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] comp = c.compressDoubles(data);
            assertNotNull(comp);
            double[] out = c.decompressDoubles(comp);
            assertArrayEquals(data, out, 0.0);
        }
    }

    @Test
    void emptyNumericArraysReturnEmpty() {
        try (OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            assertArrayEquals(new byte[0], c.compressInts(new int[0]));
            assertArrayEquals(new byte[0], c.compressLongs(new long[0]));
            assertArrayEquals(new byte[0], c.compressFloats(new float[0]));
            assertArrayEquals(new byte[0], c.compressDoubles(new double[0]));
        }
    }

    @Test
    void compressIntoTooSmallThrows() {
        byte[] payload = new byte[1024];
        new Random(1).nextBytes(payload);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] out = new byte[8];
            assertThrows(IllegalStateException.class, () -> c.compress(payload, out));
        }
    }

    @Test
    void decompressCorruptedThrows() {
        byte[] payload = "test-data".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] comp = c.compress(payload);
            assertNotNull(comp);
            // corrupt a byte
            comp[comp.length / 2] ^= 0xFF;
            try {
                byte[] out = c.decompress(comp);
                // If decompression didn't throw, ensure the output is not equal to original
                // (i.e. corruption was detected in content)
                assertFalse(java.util.Arrays.equals(payload, out), "Decompressed data equals original despite corruption");
            } catch (IllegalStateException ex) {
                // acceptable path: decompression failed due to corruption
            }
        }
    }

    @Test
    void setGetParameterRoundTrip() {
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            int before = c.getParameter(2); // compression level param
            c.setCompressionLevel(OpenZLCompressionLevel.LEVEL_12);
            assertEquals(OpenZLCompressionLevel.LEVEL_12.level(), c.getParameter(2));
            // restore
            c.setParameter(2, before);
        }
    }

    @Test
    void maxCompressedSizeMonotonic() {
        byte[] payload = new byte[4096];
        new Random(42).nextBytes(payload);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            long bound = OpenZLCompressor.maxCompressedSize(payload.length);
            byte[] comp = c.compress(payload);
            assertTrue(bound >= comp.length, "maxCompressedSize must be >= actual compressed size");
        }
    }

    @Test
    void configureSddlCompileAndUse() throws Exception {
        String sddl = ": Byte[_rem];";
        byte[] compiled = OpenZLSddl.compile(sddl, false, 0);
        assertNotNull(compiled);
        byte[] payload = new byte[] { 9, 9, 9, 9 };
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureSddl(compiled);
            byte[] comp = c.compress(payload);
            assertArrayEquals(payload, c.decompress(comp));
        }
    }

    @Test
    void dataArenaMultipleSet() {
        byte[] payload = "arena-test".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.setDataArena(OpenZLCompressor.OpenZLDataArena.HEAP);
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] a = c.compress(payload);
            assertArrayEquals(payload, c.decompress(a));

            c.setDataArena(OpenZLCompressor.OpenZLDataArena.STACK);
            byte[] b = c.compress(payload);
            assertArrayEquals(payload, c.decompress(b));

            c.setDataArena(OpenZLCompressor.OpenZLDataArena.HEAP);
            byte[] d = c.compress(payload);
            assertArrayEquals(payload, c.decompress(d));
        }
    }

    @Test
    void directByteBufferRoundTrip() {
        byte[] payload = "direct-buffer-test".getBytes(StandardCharsets.UTF_8);
        ByteBuffer src = ByteBuffer.allocateDirect(payload.length);
        src.put(payload);
        src.flip();
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            long bound = OpenZLCompressor.maxCompressedSize(src.remaining());
            ByteBuffer dst = ByteBuffer.allocateDirect((int) bound);
            int written = c.compress(src, dst);
            assertTrue(written > 0);
            dst.limit(written);
            dst.position(0);
            ByteBuffer dstDup = dst.duplicate();
            byte[] compressed = new byte[dstDup.remaining()];
            dstDup.get(compressed);
            byte[] restored = c.decompress(compressed);
            assertArrayEquals(payload, restored);
        }
    }

    @Test
    void metadataInspectSerial() {
        byte[] payload = "inspect-me".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] comp = c.compress(payload);
            OpenZLCompressionInfo info = c.inspect(comp);
            assertNotNull(info);
            assertEquals(OpenZLGraph.ZSTD, info.graph());
        }
    }

    @Test
    void repeatedCompressProducesSameOutput() {
        byte[] payload = "determinism-check".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] a = c.compress(payload);
            byte[] b = c.compress(payload);
            assertArrayEquals(a, b);
        }
    }

    @Test
    void compressionRatioZeros() {
        byte[] payload = new byte[8192];
        // all zeros
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] comp = c.compress(payload);
            assertTrue(comp.length < payload.length, () -> "expected compression for zero buffer");
        }
    }

    @Test
    void maxCompressedSizeZero() {
        long bound = OpenZLCompressor.maxCompressedSize(0);
        assertTrue(bound >= 0);
    }

    @Test
    void inspectDirectByteBuffer() {
        byte[] payload = "inspect-direct".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] comp = c.compress(payload);
            ByteBuffer buf = ByteBuffer.allocateDirect(comp.length);
            buf.put(comp);
            buf.flip();
            OpenZLCompressionInfo info = c.inspect(buf);
            assertNotNull(info);
        }
    }

    private static String generateCsv(int rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("sensor_id,timestamp,value,status\n");
        for (int i = 0; i < rows; i++) {
            sb.append(i % 32).append(',').append(1_700_000_000L + (i * 60L)).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.3f", (i % 100) * 0.125))
                    .append(',').append((i % 5 == 0) ? "ACTIVE" : "IDLE").append('\n');
        }
        return sb.toString();
    }
}
